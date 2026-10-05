package com.github.lechandonga.localobjectstorejava.store;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对象存储核心业务服务：在 {@link ContentStore}（内容层）与 {@link MetadataStore}（元数据层）
 * 之上实现版本、墓碑、快照、回滚、回收与崩溃恢复语义。
 *
 * <p>线程模型：同一 key 的写操作通过分段锁串行化；快照/GC 通过全局元数据锁串行化。
 * blob 的提交与元数据的落盘都是原子操作，因此跨进程/崩溃场景同样自洽。
 */
@Service
public class ObjectStoreService {

    private final ContentStore contentStore;
    private final MetadataStore metadataStore;
    private final StorageProperties properties;

    private final StripedLocks keyLocks = new StripedLocks(64);
    /** 保护快照集合与 GC 引用计算，避免"快照释放/创建"与"GC 扫描"交错导致误删。 */
    private final ReentrantLock gcLock = new ReentrantLock();

    public ObjectStoreService(ContentStore contentStore, MetadataStore metadataStore,
                              StorageProperties properties) {
        this.contentStore = contentStore;
        this.metadataStore = metadataStore;
        this.properties = properties;
    }

    /**
     * 启动恢复：清理上次崩溃残留的上传临时文件与元数据暂存文件。
     * blob 与元数据都通过原子替换落盘，因此磁盘状态只会是"提交前/提交后"之一，
     * 不会出现半截对象；blob 已提交而元数据未提交的情况只会留下无引用孤儿，
     * 由 GC 在超过保留期后回收，不存在永远清不掉的数据。
     */
    @PostConstruct
    public void recover() {
        contentStore.recoverTempFiles();
        metadataStore.cleanupStagedTempFiles();
    }

    // ---- 上传 / 下载 / 删除 ----

    /**
     * 幂等上传：相同内容总是产生相同 checksum、共享同一份 blob；
     * 但每次上传都会让 key 的版本号前进（记录一次新的写入事实）。
     *
     * @return 新版本信息
     * @throws ChecksumMismatchException 声明校验和与实际内容不符
     */
    public VersionInfo put(String key, InputStream content, String declaredChecksum) {
        // 先内容寻址落盘：失败（如校验和不符）不会触碰元数据，不会产生半截对象。
        ContentStore.PutResult result = contentStore.put(content, declaredChecksum);
        String checksum = result.checksum();
        ReentrantLock lock = keyLocks.lockFor(key);
        lock.lock();
        try {
            KeyMetadata meta = metadataStore.loadKey(key).orElseGet(() -> new KeyMetadata(key));
            long version = meta.getNextVersion();
            VersionInfo info = VersionInfo.content(version, checksum, result.size(), System.currentTimeMillis(), "PUT");
            meta.getVersions().add(info);
            meta.setNextVersion(version + 1);
            meta.setCurrentVersion(version);
            metadataStore.saveKey(meta);
            return info;
        } finally {
            lock.unlock();
        }
    }

    /** 读取 key 的当前版本；返回前对内容做完整性校验。 */
    public ContentRead read(String key) {
        VersionInfo version = currentVersion(key);
        return openVersion(key, version);
    }

    /** 读取指定历史版本（含墓碑判定）。 */
    public ContentRead readVersion(String key, long versionNumber) {
        VersionInfo version = requireVersion(key, versionNumber);
        return openVersion(key, version);
    }

    private ContentRead openVersion(String key, VersionInfo version) {
        contentStore.verify(version.checksum());
        InputStream stream = contentStore.open(version.checksum());
        return new ContentRead(key, version, stream);
    }

    /** 删除 = 追加墓碑版本；历史版本仍可列举/读取。 */
    public VersionInfo delete(String key) {
        ReentrantLock lock = keyLocks.lockFor(key);
        lock.lock();
        try {
            KeyMetadata meta = metadataStore.loadKey(key).orElseGet(() -> new KeyMetadata(key));
            long version = meta.getNextVersion();
            VersionInfo tombstone = VersionInfo.tombstone(version, System.currentTimeMillis());
            meta.getVersions().add(tombstone);
            meta.setNextVersion(version + 1);
            meta.setCurrentVersion(version);
            metadataStore.saveKey(meta);
            return tombstone;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 彻底清除 key 的全部版本链（含墓碑与历史内容版本）。
     * 之后 blob 是否能回收取决于是否还有其他 key/快照引用——内容寻址层天然处理共享。
     * 要求当前版本必须是墓碑（即先逻辑删除再物理清除），防止误删存活对象。
     */
    public void purgeHistory(String key) {
        ReentrantLock lock = keyLocks.lockFor(key);
        lock.lock();
        try {
            KeyMetadata meta = metadataStore.loadKey(key)
                    .orElseThrow(() -> new ObjectNotFoundException(key));
            VersionInfo current = requireVersionIn(meta, meta.getCurrentVersion());
            if (!current.tombstone()) {
                throw new StoreException("object is alive, delete it before purging history: " + key);
            }
            metadataStore.purgeKey(key);
        } finally {
            lock.unlock();
        }
    }

    // ---- 版本 ----

    public List<VersionInfo> listVersions(String key) {
        KeyMetadata meta = metadataStore.loadKey(key)
                .orElseThrow(() -> new ObjectNotFoundException(key));
        return List.copyOf(meta.getVersions());
    }

    /**
     * 回滚：把当前指针移动到一个已存在的非墓碑版本，不删除任何历史版本。
     * 回滚后旧版本、墓碑、被回滚的版本全部仍在版本链中，只是"当前可见版本"发生变化。
     */
    public VersionInfo rollback(String key, long versionNumber) {
        ReentrantLock lock = keyLocks.lockFor(key);
        lock.lock();
        try {
            KeyMetadata meta = metadataStore.loadKey(key)
                    .orElseThrow(() -> new ObjectNotFoundException(key));
            VersionInfo target = requireVersionIn(meta, versionNumber);
            if (target.tombstone()) {
                throw new StoreException("cannot rollback to a tombstone version: " + key + "#v" + versionNumber);
            }
            contentStore.verify(target.checksum());
            meta.setCurrentVersion(versionNumber);
            metadataStore.saveKey(meta);
            return target;
        } finally {
            lock.unlock();
        }
    }

    private VersionInfo currentVersion(String key) {
        KeyMetadata meta = metadataStore.loadKey(key)
                .orElseThrow(() -> new ObjectNotFoundException(key));
        long current = meta.getCurrentVersion();
        VersionInfo version = requireVersionIn(meta, current);
        if (version.tombstone()) {
            throw new ObjectDeletedException(key, current);
        }
        return version;
    }

    private VersionInfo requireVersion(String key, long versionNumber) {
        KeyMetadata meta = metadataStore.loadKey(key)
                .orElseThrow(() -> new ObjectNotFoundException(key));
        VersionInfo version = requireVersionIn(meta, versionNumber);
        if (version.tombstone()) {
            throw new ObjectDeletedException(key, versionNumber);
        }
        return version;
    }

    private static VersionInfo requireVersionIn(KeyMetadata meta, long versionNumber) {
        return meta.getVersions().stream()
                .filter(v -> v.version() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new VersionNotFoundException(meta.getKey(), versionNumber));
    }

    // ---- 快照 ----

    /**
     * 为当前所有存活对象创建只读快照，记录每个 key 当前版本号与内容 checksum。
     * 快照保存的是引用：在快照释放前，被引用的 blob 不会被 GC 回收。
     */
    public SnapshotInfo createSnapshot() {
        gcLock.lock();
        try {
            Map<String, SnapshotInfo.SnapshotRef> refs = new LinkedHashMap<>();
            for (String key : metadataStore.listKeys()) {
                KeyMetadata meta = metadataStore.loadKey(key).orElse(null);
                if (meta == null || meta.getCurrentVersion() <= 0) {
                    continue;
                }
                VersionInfo current = requireVersionIn(meta, meta.getCurrentVersion());
                if (!current.tombstone()) {
                    refs.put(key, new SnapshotInfo.SnapshotRef(current.version(), current.checksum()));
                }
            }
            String id = UUID.randomUUID().toString();
            SnapshotInfo snapshot = new SnapshotInfo(id, System.currentTimeMillis(), refs);
            metadataStore.saveSnapshot(snapshot);
            return snapshot;
        } finally {
            gcLock.unlock();
        }
    }

    public SnapshotInfo getSnapshot(String snapshotId) {
        return metadataStore.loadSnapshot(snapshotId);
    }

    public List<SnapshotInfo> listSnapshots() {
        return metadataStore.listSnapshots();
    }

    /** 释放快照：之后它引用的内容若无其他引用即可被回收。 */
    public void releaseSnapshot(String snapshotId) {
        gcLock.lock();
        try {
            if (!metadataStore.snapshotExists(snapshotId)) {
                throw new SnapshotNotFoundException(snapshotId);
            }
            metadataStore.deleteSnapshot(snapshotId);
        } finally {
            gcLock.unlock();
        }
    }

    /** 读取快照中某个 key 当时固定的版本内容。 */
    public ContentRead readSnapshot(String snapshotId, String key) {
        SnapshotInfo snapshot = metadataStore.loadSnapshot(snapshotId);
        SnapshotInfo.SnapshotRef ref = snapshot.refs().get(key);
        if (ref == null) {
            throw new ObjectNotFoundException(key + " (not pinned by snapshot " + snapshotId + ")");
        }
        // 快照只依赖内容 blob，不依赖 key 的版本链（版本链可能已被 purgeHistory 清除）。
        contentStore.verify(ref.checksum());
        VersionInfo view = VersionInfo.content(ref.version(), ref.checksum(),
                blobSize(ref.checksum()), snapshot.createdAt(), "SNAPSHOT");
        return new ContentRead(key, view, contentStore.open(ref.checksum()));
    }

    private long blobSize(String checksum) {
        try {
            return java.nio.file.Files.size(contentStore.blobsDir()
                    .resolve(checksum.substring(0, 2)).resolve(checksum));
        } catch (java.io.IOException e) {
            throw new ContentCorruptedException("cannot stat content: " + checksum, e);
        }
    }

    // ---- 回收 ----

    /**
     * 回收规则：一个 blob 仅当同时满足以下条件才会被删除：
     * <ol>
     *   <li>不被任何 key 的任何版本引用（历史版本也保护其 blob）；</li>
     *   <li>不被任何存活快照引用；</li>
     *   <li>不在进行中的读取集合内；</li>
     *   <li>最后修改时间早于 now - retention（保留期）。</li>
     * </ol>
     * 引用集合的计算与内容删除在同一把 GC 锁保护的临界区内完成，
     * 保证与快照释放、版本写入并发时不会误删在用对象。
     */
    public GcReport garbageCollect(Duration retention) {
        gcLock.lock();
        try {
            Set<String> referenced = collectReferencedChecksums();
            long before = contentStore.blobCount();
            List<String> removed = contentStore.garbageCollect(referenced, retention);
            long after = contentStore.blobCount();
            return new GcReport(referenced.size(), before, after, removed);
        } finally {
            gcLock.unlock();
        }
    }

    /** 测试便捷入口：使用配置中的保留期。 */
    public GcReport garbageCollect() {
        return garbageCollect(Duration.ofSeconds(properties.getRetentionSeconds()));
    }

    private Set<String> collectReferencedChecksums() {
        Set<String> referenced = new HashSet<>();
        for (String key : metadataStore.listKeys()) {
            metadataStore.loadKey(key).ifPresent(meta -> {
                for (VersionInfo version : meta.getVersions()) {
                    if (version.checksum() != null) {
                        referenced.add(version.checksum());
                    }
                }
            });
        }
        for (SnapshotInfo snapshot : metadataStore.listSnapshots()) {
            referenced.addAll(snapshot.refs().values().stream()
                    .map(SnapshotInfo.SnapshotRef::checksum)
                    .toList());
        }
        return referenced;
    }

    /** 列出所有 key 的当前状态（管理/诊断接口）。 */
    public List<Map<String, Object>> listObjects() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : metadataStore.listKeys()) {
            KeyMetadata meta = metadataStore.loadKey(key).orElse(null);
            if (meta == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("currentVersion", meta.getCurrentVersion());
            row.put("versionCount", meta.getVersions().size());
            VersionInfo current = meta.getVersions().stream()
                    .filter(v -> v.version() == meta.getCurrentVersion())
                    .findFirst().orElse(null);
            row.put("deleted", current != null && current.tombstone());
            if (current != null && !current.tombstone()) {
                row.put("checksum", current.checksum());
                row.put("size", current.size());
            }
            result.add(row);
        }
        return result;
    }

    // ---- 返回值类型 ----

    /** 一次内容读取：元信息 + 内容流（调用方负责关闭）。 */
    public record ContentRead(String key, VersionInfo version, InputStream stream) implements AutoCloseable {
        public String checksum() {
            return version.checksum();
        }

        public long size() {
            return version.size();
        }

        @Override
        public void close() throws java.io.IOException {
            stream.close();
        }
    }

    /** 一次 GC 的结果报告。 */
    public record GcReport(long referencedBlobs, long blobsBefore, long blobsAfter, List<String> removedChecksums) {
        public long removedCount() {
            return blobsBefore - blobsAfter;
        }
    }
}
