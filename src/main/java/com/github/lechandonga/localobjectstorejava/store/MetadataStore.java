package com.github.lechandonga.localobjectstorejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * JSON 文件元数据存储。
 *
 * <p>每个 key / snapshot 各对应一个 JSON 文件，写入采用
 * "写同目录临时文件 + ATOMIC_MOVE 替换"：崩溃后要么是旧版本要么是新版本，不会出现半截 JSON。
 * blob 是内容寻址的不可变文件，元数据只记录引用关系，重启后双方对账即可自洽恢复。
 */
@Component
public class MetadataStore {

    private final Path keysDir;
    private final Path snapshotsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetadataStore(StorageProperties properties) {
        Path root = Path.of(properties.getRoot());
        this.keysDir = root.resolve("meta").resolve("keys");
        this.snapshotsDir = root.resolve("meta").resolve("snapshots");
        try {
            Files.createDirectories(keysDir);
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            throw new StoreException("cannot initialize metadata directories", e);
        }
    }

    public Path keysDir() {
        return keysDir;
    }

    // ---- key metadata ----

    /** 加载 key 元数据；文件不存在返回空（调用方据此区分"从未存在"）。 */
    public Optional<KeyMetadata> loadKey(String key) {
        Path file = keyFile(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), KeyMetadata.class));
        } catch (IOException e) {
            throw new StoreException("corrupted metadata for key: " + key, e);
        }
    }

    public void saveKey(KeyMetadata metadata) {
        atomicWriteJson(keyFile(metadata.getKey()), metadata);
    }

    public List<String> listKeys() {
        return listJsonIds(keysDir);
    }

    /** 物理删除 key 的元数据文件（purgeHistory 用）。 */
    public void purgeKey(String key) {
        try {
            Files.deleteIfExists(keyFile(key));
        } catch (IOException e) {
            throw new StoreException("failed purging key metadata: " + key, e);
        }
    }

    /** 启动恢复：清理元数据原子写过程中崩溃残留的 meta-*.tmp 暂存文件。 */
    public void cleanupStagedTempFiles() {
        cleanupStagedIn(keysDir);
        cleanupStagedIn(snapshotsDir);
    }

    private void cleanupStagedIn(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().startsWith("meta-")
                            && p.getFileName().toString().endsWith(".tmp"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 下次启动再清理
                        }
                    });
        } catch (IOException e) {
            throw new StoreException("failed cleaning staged metadata in " + dir, e);
        }
    }

    // ---- snapshot metadata ----

    public SnapshotInfo loadSnapshot(String snapshotId) {
        Path file = snapshotFile(snapshotId);
        if (!Files.isRegularFile(file)) {
            throw new SnapshotNotFoundException(snapshotId);
        }
        try {
            SnapshotMetadata raw = mapper.readValue(file.toFile(), SnapshotMetadata.class);
            Map<String, SnapshotInfo.SnapshotRef> refs = new LinkedHashMap<>();
            raw.getRefs().forEach((k, v) ->
                    refs.put(k, new SnapshotInfo.SnapshotRef(v.getVersion(), v.getChecksum())));
            return new SnapshotInfo(raw.getId(), raw.getCreatedAt(), refs);
        } catch (IOException e) {
            throw new StoreException("corrupted snapshot metadata: " + snapshotId, e);
        }
    }

    public boolean snapshotExists(String snapshotId) {
        return Files.isRegularFile(snapshotFile(snapshotId));
    }

    public void saveSnapshot(SnapshotInfo snapshot) {
        Map<String, SnapshotMetadata.Ref> refs = new LinkedHashMap<>();
        snapshot.refs().forEach((k, v) ->
                refs.put(k, new SnapshotMetadata.Ref(v.version(), v.checksum())));
        atomicWriteJson(snapshotFile(snapshot.id()),
                new SnapshotMetadata(snapshot.id(), snapshot.createdAt(), refs));
    }

    public void deleteSnapshot(String snapshotId) {
        try {
            Files.deleteIfExists(snapshotFile(snapshotId));
        } catch (IOException e) {
            throw new StoreException("failed deleting snapshot: " + snapshotId, e);
        }
    }

    public List<SnapshotInfo> listSnapshots() {
        List<SnapshotInfo> result = new ArrayList<>();
        for (String id : listJsonIds(snapshotsDir)) {
            result.add(loadSnapshot(id));
        }
        result.sort(java.util.Comparator.comparingLong(SnapshotInfo::createdAt));
        return result;
    }

    // ---- internals ----

    private void atomicWriteJson(Path target, Object value) {
        Path tmp;
        try {
            tmp = Files.createTempFile(target.getParent(), "meta-", ".tmp");
        } catch (IOException e) {
            throw new StoreException("cannot create metadata temp file for " + target, e);
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程，重启恢复会清理。
            }
            throw new StoreException("failed atomically writing metadata: " + target, e);
        }
    }

    private List<String> listJsonIds(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new StoreException("failed listing metadata files in " + dir, e);
        }
    }

    private Path keyFile(String key) {
        return keysDir.resolve(encodeKey(key) + ".json");
    }

    private Path snapshotFile(String snapshotId) {
        return snapshotsDir.resolve(snapshotId + ".json");
    }

    /** key 直接作为文件名不安全，使用百分号编码（保留常见字符的可读性）。 */
    static String encodeKey(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    sb.append('%').append(String.format("%02X", b));
                }
            }
        }
        return sb.toString();
    }
}
