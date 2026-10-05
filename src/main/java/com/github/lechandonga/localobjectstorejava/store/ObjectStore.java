package com.github.lechandonga.localobjectstorejava.store;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.localobjectstorejava.config.StoreProperties;

import jakarta.annotation.PostConstruct;

/**
 * 本地对象存储核心。
 *
 * 存储布局（根目录由 objectstore.root 指定）：
 * - blobs/ab/cd/&lt;sha256&gt;        内容寻址 blob，不可变
 * - blobs/tmp/&lt;uuid&gt;.tmp          写入中的临时文件，提交时原子移动，启动时清理
 * - meta/ab/cd/&lt;sha256(key)&gt;.json 每个对象键一个元数据文件
 * - meta/tmp/&lt;uuid&gt;.tmp           元数据原子替换用临时文件
 */
@Component
public class ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStore.class);

    private final Path root;
    private final Path blobDir;
    private final Path metaDir;
    private final Duration retention;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 每个对象键一把锁，串行化同键上的写/删/回滚/快照操作。 */
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    /**
     * GC 与元数据提交之间的协调锁：会产生新 blob 引用的操作在「提交元数据」瞬间持有读锁，
     * GC 整体持有写锁，保证 GC 不会删除一个元数据刚提交引用的 blob。
     */
    private final ReentrantReadWriteLock commitLock = new ReentrantReadWriteLock();

    public Path root() {
        return root;
    }

    public ObjectStore(StoreProperties properties) {
        this.root = properties.getRoot().toAbsolutePath().normalize();
        this.blobDir = root.resolve("blobs");
        this.metaDir = root.resolve("meta");
        this.retention = properties.getRetention();
    }

    @PostConstruct
    public synchronized void init() {
        try {
            Files.createDirectories(blobDir);
            Files.createDirectories(metaDir);
            cleanupStaging(blobDir.resolve("tmp"));
            cleanupStaging(metaDir.resolve("tmp"));
            log.info("ObjectStore initialized at {}, retention={}", root, retention);
        } catch (IOException e) {
            throw new UncheckedIOException("init store failed: " + root, e);
        }
    }

    private static void cleanupStaging(Path tmpDir) throws IOException {
        if (!Files.isDirectory(tmpDir)) {
            return;
        }
        try (var paths = Files.list(tmpDir)) {
            paths.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    public static final class UploadResult {
        public final long version;
        public final String checksum;
        public final long size;
        public final boolean deduplicated;

        UploadResult(long version, String checksum, long size, boolean deduplicated) {
            this.version = version;
            this.checksum = checksum;
            this.size = size;
            this.deduplicated = deduplicated;
        }
    }

    public static final class ReadResult {
        public final long version;
        public final String checksum;
        public final long size;
        public final Path path;

        ReadResult(long version, String checksum, long size, Path path) {
            this.version = version;
            this.checksum = checksum;
            this.size = size;
            this.path = path;
        }
    }

    public static final class GcResult {
        public int versionsPruned;
        public int blobsDeleted;
        public int metadataDeleted;
        public long bytesReclaimed;
    }

    /** 上传（覆盖写）。expectedChecksum 为十六进制 sha256，必须与实际内容一致。 */
    public UploadResult put(String key, String expectedChecksum, InputStream in) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            throw new StoreException(HttpStatus.BAD_REQUEST, "CHECKSUM_REQUIRED",
                    "上传必须通过 X-Checksum-SHA256 声明内容的 sha256");
        }
        String expected = normalizeChecksum(expectedChecksum);

        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Path tmp = newBlobTmp();
            String actual;
            long size;
            try {
                MessageDigest digest = sha256();
                try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                        DigestInputStream dis = new DigestInputStream(new BufferedInputStream(in), digest)) {
                    size = dis.transferTo(out);
                    actual = toHex(digest.digest());
                }
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw new StoreException(HttpStatus.BAD_GATEWAY, "WRITE_FAILED",
                        "写入内容失败: " + e.getMessage());
            }

            if (!actual.equals(expected)) {
                deleteQuietly(tmp);
                throw new StoreException(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH",
                        "声明的校验和 " + expected + " 与实际内容 " + actual + " 不一致");
            }

            Path target = blobPath(actual);
            boolean deduplicated = Files.exists(target);
            if (!deduplicated) {
                commitBlob(tmp, target);
            } else {
                deleteQuietly(tmp);
            }

            Metadata md = readOrCreate(key);
            long version = nextVersion(md);
            md.versions.add(Metadata.Version.live(version, actual, size, System.currentTimeMillis()));
            md.current = version;
            commitMetadata(md);
            log.info("PUT key={} version={} checksum={} size={} deduplicated={}",
                    key, version, actual, size, deduplicated);
            return new UploadResult(version, actual, size, deduplicated);
        } finally {
            lock.unlock();
        }
    }

    /** 读取当前版本。从未存在与已删除（墓碑）分别抛出 NOT_FOUND / DELETED。 */
    public ReadResult get(String key) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readMetadata(key);
            Metadata.Version current = currentLiveOrNull(md);
            if (md == null || current == null) {
                throw notFound(key, md);
            }
            Path path = requireBlob(current.checksum, key, current.version);
            return new ReadResult(current.version, current.checksum, current.size, path);
        } finally {
            lock.unlock();
        }
    }

    /** 读取指定版本；墓碑版本返回 DELETED，不存在的版本返回 VERSION_NOT_FOUND。 */
    public ReadResult getVersion(String key, long version) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readMetadata(key);
            if (md == null) {
                throw new StoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在: " + key);
            }
            Metadata.Version v = findVersion(md, version);
            if (v == null) {
                throw new StoreException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND",
                        "版本不存在: " + key + "#v" + version);
            }
            if (v.deleted) {
                throw new StoreException(HttpStatus.GONE, "DELETED",
                        "该版本是删除墓碑: " + key + "#v" + version);
            }
            Path path = requireBlob(v.checksum, key, version);
            return new ReadResult(v.version, v.checksum, v.size, path);
        } finally {
            lock.unlock();
        }
    }

    /** 读取快照所固定的内容。 */
    public ReadResult getSnapshot(String key, String snapshotId) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readMetadata(key);
            Metadata.Snapshot snap = md == null ? null : findSnapshot(md, snapshotId);
            if (snap == null) {
                throw new StoreException(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND",
                        "快照不存在: " + key + "/" + snapshotId);
            }
            Path path = requireBlob(snap.checksum, key, snap.version);
            return new ReadResult(snap.version, snap.checksum, snap.size, path);
        } finally {
            lock.unlock();
        }
    }

    /** 墓碑删除：追加一个墓碑版本。 */
    public long delete(String key) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readOrCreate(key);
            long version = nextVersion(md);
            md.versions.add(Metadata.Version.tombstone(version, System.currentTimeMillis()));
            md.current = version;
            commitMetadata(md);
            log.info("DELETE key={} tombstone=v{}", key, version);
            return version;
        } finally {
            lock.unlock();
        }
    }

    public List<Metadata.Version> listVersions(String key) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readMetadata(key);
            if (md == null) {
                throw new StoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在: " + key);
            }
            return new ArrayList<>(md.versions);
        } finally {
            lock.unlock();
        }
    }

    /** 回滚：把指定历史版本的内容重新作为当前版本（追加新版本号），历史版本全部保留。 */
    public long rollback(String key, long version) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readMetadata(key);
            if (md == null) {
                throw new StoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在: " + key);
            }
            Metadata.Version target = findVersion(md, version);
            if (target == null) {
                throw new StoreException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND",
                        "版本不存在: " + key + "#v" + version);
            }
            long newVersion = nextVersion(md);
            Metadata.Version restored;
            if (target.deleted) {
                restored = Metadata.Version.tombstone(newVersion, System.currentTimeMillis());
            } else {
                requireBlob(target.checksum, key, version);
                restored = Metadata.Version.live(newVersion, target.checksum, target.size,
                        System.currentTimeMillis());
            }
            md.versions.add(restored);
            md.current = newVersion;
            commitMetadata(md);
            log.info("ROLLBACK key={} from=v{} new=v{}", key, version, newVersion);
            return newVersion;
        } finally {
            lock.unlock();
        }
    }

    public Metadata.Snapshot createSnapshot(String key) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readMetadata(key);
            Metadata.Version current = currentLiveOrNull(md);
            if (md == null || current == null) {
                throw notFound(key, md);
            }
            requireBlob(current.checksum, key, current.version);
            Metadata.Snapshot snap = new Metadata.Snapshot();
            snap.id = UUID.randomUUID().toString();
            snap.version = current.version;
            snap.checksum = current.checksum;
            snap.size = current.size;
            snap.createdAt = System.currentTimeMillis();
            md.snapshots.add(snap);
            commitMetadata(md);
            log.info("SNAPSHOT key={} id={} pinned=v{} checksum={}", key, snap.id, snap.version,
                    snap.checksum);
            return snap;
        } finally {
            lock.unlock();
        }
    }

    public List<Metadata.Snapshot> listSnapshots(String key) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readMetadata(key);
            if (md == null) {
                throw new StoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在: " + key);
            }
            return new ArrayList<>(md.snapshots);
        } finally {
            lock.unlock();
        }
    }

    /** 释放快照；重复释放返回 SNAPSHOT_NOT_FOUND。 */
    public void releaseSnapshot(String key, String snapshotId) {
        ReentrantLock lock = keyLock(key);
        lock.lock();
        try {
            Metadata md = readMetadata(key);
            if (md == null || findSnapshot(md, snapshotId) == null) {
                throw new StoreException(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND",
                        "快照不存在: " + key + "/" + snapshotId);
            }
            md.snapshots.removeIf(s -> s.id.equals(snapshotId));
            commitMetadata(md);
            log.info("RELEASE snapshot key={} id={}", key, snapshotId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 空间回收：
     * 1. 裁剪每个键上「既非当前版本、也无快照引用、且超过保留期」的旧版本记录；
     * 2. 删除全量元数据中已无任何引用、且落盘时间超过保留期的 blob 文件。
     */
    public GcResult garbageCollect() {
        GcResult result = new GcResult();

        List<Path> metaFiles = listMetaFiles();
        Map<String, Metadata> snapshot = new TreeMap<>();
        for (Path metaFile : metaFiles) {
            try {
                Metadata md = mapper.readValue(metaFile.toFile(), Metadata.class);
                snapshot.put(md.key, md);
            } catch (IOException e) {
                log.warn("GC 跳过无法解析的元数据文件 {}: {}", metaFile, e.getMessage());
            }
        }

        List<ReentrantLock> locks = new ArrayList<>();
        for (String key : snapshot.keySet()) {
            ReentrantLock l = keyLock(key);
            l.lock();
            locks.add(l);
        }
        commitLock.writeLock().lock();
        try {
            long cutoff = System.currentTimeMillis() - retention.toMillis();
            Set<String> referenced = new HashSet<>();

            for (Map.Entry<String, Metadata> entry : snapshot.entrySet()) {
                String key = entry.getKey();
                Metadata disk = readMetadata(key);
                if (disk == null) {
                    continue;
                }
                Metadata.Version current = findVersion(disk, disk.current == null ? -1 : disk.current);
                Set<String> pinned = new HashSet<>();
                for (Metadata.Snapshot s : disk.snapshots) {
                    if (s.checksum != null) {
                        pinned.add(s.checksum);
                    }
                }

                boolean changed = false;
                List<Metadata.Version> kept = new ArrayList<>();
                for (Metadata.Version v : disk.versions) {
                    boolean isCurrent = current != null && v.version == current.version;
                    boolean pinnedBySnapshot = !v.deleted && pinned.contains(v.checksum);
                    boolean expired = v.createdAt <= cutoff;
                    // 当前版本只有在「过期墓碑且无任何快照」时才随元数据整体清理
                    boolean removableCurrentTombstone =
                            isCurrent && v.deleted && disk.snapshots.isEmpty() && expired;
                    if ((!isCurrent && !pinnedBySnapshot && expired) || removableCurrentTombstone) {
                        changed = true;
                        result.versionsPruned++;
                    } else {
                        kept.add(v);
                        if (v.checksum != null) {
                            referenced.add(v.checksum);
                        }
                    }
                }
                for (Metadata.Snapshot s : disk.snapshots) {
                    if (s.checksum != null) {
                        referenced.add(s.checksum);
                    }
                }

                if (kept.isEmpty() && disk.snapshots.isEmpty()) {
                    try {
                        Files.deleteIfExists(metaPath(key));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    result.metadataDeleted++;
                    continue;
                }
                if (changed) {
                    disk.versions = kept;
                    if (current != null && findVersion(disk, disk.current) == null) {
                        disk.current = kept.isEmpty() ? null : kept.get(kept.size() - 1).version;
                    }
                    writeMetadataAtomically(disk);
                }
            }

            deleteUnreferencedBlobs(referenced, cutoff, result);
        } finally {
            commitLock.writeLock().unlock();
            for (ReentrantLock l : locks) {
                l.unlock();
            }
        }
        log.info("GC result: versionsPruned={}, blobsDeleted={}, metadataDeleted={}, bytesReclaimed={}",
                result.versionsPruned, result.blobsDeleted, result.metadataDeleted,
                result.bytesReclaimed);
        return result;
    }

    // ----- 内部实现 -----

    private ReentrantLock keyLock(String key) {
        return keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    private Path shardedPath(Path base, String hex) {
        return base.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4));
    }

    private Path blobPath(String checksum) {
        return shardedPath(blobDir, checksum).resolve(checksum);
    }

    private Path metaPath(String key) {
        return shardedPath(metaDir, sha256Hex(key.getBytes(StandardCharsets.UTF_8)))
                .resolve(sha256Hex(key.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    private Path newBlobTmp() {
        Path tmpDir = blobDir.resolve("tmp");
        try {
            Files.createDirectories(tmpDir);
        } catch (IOException e) {
            throw new UncheckedIOException("create tmp blob dir failed", e);
        }
        return tmpDir.resolve(UUID.randomUUID() + ".tmp");
    }

    private void commitBlob(Path tmp, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            if (Files.exists(target)) {
                return;
            }
            throw new StoreException(HttpStatus.INTERNAL_SERVER_ERROR, "COMMIT_FAILED",
                    "内容落盘提交失败: " + e.getMessage());
        }
    }

    private Metadata readOrCreate(String key) {
        Metadata md = readMetadata(key);
        if (md == null) {
            md = new Metadata();
            md.key = key;
        }
        return md;
    }

    private Metadata readMetadata(String key) {
        Path file = metaPath(key);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.readValue(file.toFile(), Metadata.class);
        } catch (IOException e) {
            throw new StoreException(HttpStatus.INTERNAL_SERVER_ERROR, "METADATA_CORRUPT",
                    "元数据损坏且无法解析: " + key);
        }
    }

    private void commitMetadata(Metadata md) {
        commitLock.readLock().lock();
        try {
            writeMetadataAtomically(md);
        } finally {
            commitLock.readLock().unlock();
        }
    }

    private void writeMetadataAtomically(Metadata md) {
        Path target = metaPath(md.key);
        Path tmpDir = metaDir.resolve("tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.createDirectories(tmpDir);
            Path tmp = Files.createFile(tmpDir.resolve(UUID.randomUUID() + ".tmp"));
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, md);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StoreException(HttpStatus.INTERNAL_SERVER_ERROR, "METADATA_WRITE_FAILED",
                    "元数据写入失败: " + md.key);
        }
    }

    /** 校验 blob 存在且内容 sha256 与元数据一致；任何不一致都报可区分错误，绝不返回错误数据。 */
    private Path requireBlob(String checksum, String key, long version) {
        Path path = blobPath(checksum);
        if (!Files.exists(path)) {
            throw new StoreException(HttpStatus.INTERNAL_SERVER_ERROR, "BLOB_MISSING",
                    "元数据引用的内容缺失（悬空引用）: " + key + "#v" + version + " checksum=" + checksum);
        }
        String actual = hashFile(path);
        if (!checksum.equals(actual)) {
            throw new StoreException(HttpStatus.INTERNAL_SERVER_ERROR, "BLOB_CORRUPT",
                    "内容校验和不匹配（内容损坏）: " + key + "#v" + version
                            + ", expected=" + checksum + ", actual=" + actual);
        }
        return path;
    }

    private void deleteUnreferencedBlobs(Set<String> referenced, long cutoff, GcResult result) {
        if (!Files.isDirectory(blobDir)) {
            return;
        }
        try (var shards = Files.list(blobDir)) {
            shards.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().equals("tmp"))
                    .forEach(shard -> {
                        try (var subs = Files.list(shard)) {
                            subs.filter(Files::isDirectory).forEach(sub -> {
                                try (var files = Files.list(sub)) {
                                    files.filter(Files::isRegularFile).forEach(blob -> {
                                        String name = blob.getFileName().toString();
                                        if (referenced.contains(name)) {
                                            return;
                                        }
                                        try {
                                            if (Files.getLastModifiedTime(blob).toMillis() > cutoff) {
                                                return;
                                            }
                                            long size = Files.size(blob);
                                            Files.deleteIfExists(blob);
                                            result.blobsDeleted++;
                                            result.bytesReclaimed += size;
                                        } catch (IOException e) {
                                            log.warn("删除孤儿 blob 失败 {}: {}", blob, e.getMessage());
                                        }
                                    });
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("scan blobs failed", e);
        }
    }

    private List<Path> listMetaFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(metaDir)) {
            return files;
        }
        try (var shards = Files.list(metaDir)) {
            shards.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().equals("tmp"))
                    .forEach(shard -> {
                        try (var subs = Files.list(shard)) {
                            subs.filter(Files::isDirectory).forEach(sub -> {
                                try (var fs = Files.list(sub)) {
                                    fs.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("scan meta failed", e);
        }
        return files;
    }

    private static long nextVersion(Metadata md) {
        return md.versions.stream().mapToLong(v -> v.version).max().orElse(0) + 1;
    }

    private static Metadata.Version findVersion(Metadata md, long version) {
        for (Metadata.Version v : md.versions) {
            if (v.version == version) {
                return v;
            }
        }
        return null;
    }

    private static Metadata.Snapshot findSnapshot(Metadata md, String id) {
        for (Metadata.Snapshot s : md.snapshots) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    private static Metadata.Version currentLiveOrNull(Metadata md) {
        if (md == null || md.current == null) {
            return null;
        }
        Metadata.Version v = findVersion(md, md.current);
        return v != null && !v.deleted ? v : null;
    }

    private static StoreException notFound(String key, Metadata md) {
        if (md == null || md.versions.isEmpty()) {
            return new StoreException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在: " + key);
        }
        return new StoreException(HttpStatus.GONE, "DELETED", "对象已被删除（墓碑）: " + key);
    }

    private static String normalizeChecksum(String raw) {
        String hex = raw.trim().toLowerCase();
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new StoreException(HttpStatus.BAD_REQUEST, "CHECKSUM_INVALID",
                    "校验和必须是 64 位十六进制 sha256: " + raw);
        }
        return hex;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        return toHex(sha256().digest(bytes));
    }

    private static String hashFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            DigestInputStream dis = new DigestInputStream(in, sha256());
            dis.transferTo(OutputStream.nullOutputStream());
            return toHex(dis.getMessageDigest().digest());
        } catch (IOException e) {
            throw new StoreException(HttpStatus.INTERNAL_SERVER_ERROR, "BLOB_UNREADABLE",
                    "内容无法读取: " + path);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
