package com.github.lechandonga.localobjectstorejava.store;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 内容寻址存储（content-addressable storage）。
 *
 * <p>目录模型（root 由 {@link StorageProperties} 配置）：
 * <pre>
 * root/
 *   blobs/xx/&lt;sha256&gt;      不可变内容对象，文件名即完整 SHA-256，前 2 位做分桶
 *   tmp/&lt;uuid&gt;.tmp         写入临时文件，落盘 + 校验通过后原子 rename 到 blobs
 *   meta/keys/&lt;key&gt;.json   每个对象 key 的版本元数据
 *   meta/snapshots/&lt;id&gt;.json  快照元数据
 * </pre>
 *
 * <p>去重：相同 SHA-256 的内容在磁盘上只有一份 blob，所有版本/快照共享引用。
 * 并发写相同内容：{@code Files.move(ATOMIC_MOVE)} 到同一目标路径，天然幂等，
 * 失败方会重新验证目标 blob 校验和，确保不会出现重复落盘或半写数据。
 */
@Component
public class ContentStore {

    private final Path blobsDir;
    private final Path tmpDir;

    /** 正在被读取的 blob 校验和集合，GC 跳过这些文件，保证进行中的读取不被打断。 */
    private final Set<String> activeReads = ConcurrentHashMap.newKeySet();

    /** 正在被写入（尚未提交）的临时文件名，GC 不得清理（即使超过保留期）。 */
    private final Set<String> activeTempFiles = ConcurrentHashMap.newKeySet();

    public ContentStore(StorageProperties properties) {
        Path root = Path.of(properties.getRoot());
        this.blobsDir = root.resolve("blobs");
        this.tmpDir = root.resolve("tmp");
        try {
            Files.createDirectories(blobsDir);
            Files.createDirectories(tmpDir);
        } catch (IOException e) {
            throw new StoreException("cannot initialize storage directories under " + root, e);
        }
    }

    public Path blobsDir() {
        return blobsDir;
    }

    public Path tmpDir() {
        return tmpDir;
    }

    /**
     * 原子写入一份内容。
     *
     * @param in              内容输入流
     * @param declaredChecksum 客户端声明的 SHA-256（可为 null 表示不校验）
     * @return 实际内容的 SHA-256 与字节数
     * @throws ChecksumMismatchException 声明校验和与实际不符
     */
    public PutResult put(InputStream in, String declaredChecksum) {
        String expected = Checksums.normalize(declaredChecksum);
        Path tmp;
        try {
            tmp = Files.createTempFile(tmpDir, "upload-", ".tmp");
        } catch (IOException e) {
            throw new StoreException("cannot create temp file", e);
        }
        activeTempFiles.add(tmp.getFileName().toString());
        MessageDigest digest = Checksums.newDigest();
        long totalSize = 0;
        try {
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE);
                 InputStream buffered = new BufferedInputStream(in)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = buffered.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    totalSize += read;
                }
            }
            String actual = Checksums.toHex(digest.digest());
            if (expected != null && !expected.equals(actual)) {
                Files.deleteIfExists(tmp);
                throw new ChecksumMismatchException(expected, actual);
            }
            commitBlob(tmp, actual);
            return new PutResult(actual, totalSize);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new StoreException("failed writing content", e);
        } finally {
            activeTempFiles.remove(tmp.getFileName().toString());
        }
    }

    /** 测试/恢复辅助：把一份已知内容原子落盘为 blob。 */
    public void materializeBlob(Path source, String checksum) {
        if (!isValidBlobPath(checksum)) {
            throw new IllegalArgumentException("invalid checksum: " + checksum);
        }
        try {
            Path target = blobPath(checksum);
            if (Files.exists(target)) {
                return;
            }
            Path tmp = Files.createTempFile(tmpDir, "restore-", ".tmp");
            try {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                commitBlob(tmp, checksum);
            } finally {
                deleteQuietly(tmp);
            }
        } catch (IOException e) {
            throw new StoreException("failed materializing blob " + checksum, e);
        }
    }

    private void commitBlob(Path tmp, String checksum) throws IOException {
        Path target = blobPath(checksum);
        Files.createDirectories(target.getParent());
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 并发写相同内容：目标可能已被另一个线程/进程提交。校验目标确认一致即可。
            if (Files.exists(target) && checksum.equals(Checksums.sha256Hex(target))) {
                deleteQuietly(tmp);
                return;
            }
            throw e;
        }
    }

    public boolean exists(String checksum) {
        return Files.isRegularFile(blobPath(checksum));
    }

    /**
     * 打开 blob 进行读取，并在读取期间把该内容标记为活跃，GC 不会回收它。
     * 调用方必须关闭返回的输入流。
     *
     * @throws ContentCorruptedException blob 缺失或校验和与元数据声明不一致
     */
    public InputStream open(String checksum) {
        Path path = blobPath(checksum);
        if (!Files.isRegularFile(path)) {
            throw new ContentCorruptedException("content missing on disk: " + checksum);
        }
        activeReads.add(checksum);
        boolean success = false;
        try {
            InputStream stream = new ActiveReadInputStream(
                    Files.newInputStream(path), checksum, path, activeReads);
            success = true;
            return stream;
        } catch (IOException e) {
            throw new ContentCorruptedException("cannot open content: " + checksum, e);
        } finally {
            if (!success) {
                activeReads.remove(checksum);
            }
        }
    }

    /**
     * 全量校验一个 blob 与元数据引用的校验和是否一致。
     *
     * @throws ContentCorruptedException 缺失或内容损坏
     */
    public void verify(String checksum) {
        Path path = blobPath(checksum);
        if (!Files.isRegularFile(path)) {
            throw new ContentCorruptedException("content missing on disk: " + checksum);
        }
        String actual;
        try {
            actual = Checksums.sha256Hex(path);
        } catch (IOException e) {
            throw new ContentCorruptedException("cannot verify content: " + checksum, e);
        }
        if (!checksum.equals(actual)) {
            throw new ContentCorruptedException(
                    "content checksum mismatch: referenced=" + checksum + ", actual=" + actual);
        }
    }

    /** 读取时的流式校验输入流：读完若 digest 不符则抛出损坏错误，绝不返回错误数据。 */
    private static final class ActiveReadInputStream extends InputStream {
        private final InputStream delegate;
        private final String checksum;
        private final Path path;
        private final Set<String> activeReads;
        private final MessageDigest digest = Checksums.newDigest();
        private boolean closed;

        private ActiveReadInputStream(InputStream delegate, String checksum, Path path,
                                      Set<String> activeReads) {
            this.delegate = delegate;
            this.checksum = checksum;
            this.path = path;
            this.activeReads = activeReads;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                digest.update((byte) b);
            } else {
                finishAndCheck();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) {
                digest.update(buf, off, n);
            } else if (n == -1) {
                finishAndCheck();
            }
            return n;
        }

        private void finishAndCheck() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                String actual = Checksums.toHex(digest.digest());
                if (!checksum.equals(actual)) {
                    throw new ContentCorruptedException(
                            "content corrupted during read: referenced=" + checksum + ", actual=" + actual);
                }
            } finally {
                activeReads.remove(checksum);
                delegate.close();
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                finishAndCheck();
            }
        }
    }

    /**
     * 回收：删除确认无引用（referenced 集合之外）且超过保留期的 blob，
     * 以及进程崩溃遗留的、超过保留期的 tmp 文件。活跃读取中的 blob 一律跳过。
     *
     * @return 被删除的 blob 校验和列表
     */
    public List<String> garbageCollect(Set<String> referencedChecksums, Duration retention) {
        Instant cutoff = Instant.now().minus(retention);
        Set<String> deleted = new HashSet<>();
        try (Stream<Path> buckets = Files.list(blobsDir)) {
            List<Path> bucketDirs = buckets.filter(Files::isDirectory).toList();
            for (Path bucket : bucketDirs) {
                try (Stream<Path> blobs = Files.list(bucket)) {
                    for (Path blob : blobs.filter(Files::isRegularFile).toList()) {
                        String checksum = blob.getFileName().toString();
                        if (referencedChecksums.contains(checksum) || activeReads.contains(checksum)) {
                            continue;
                        }
                        Instant modified = Files.getLastModifiedTime(blob).toInstant();
                        if (modified.isAfter(cutoff)) {
                            continue;
                        }
                        if (Files.deleteIfExists(blob)) {
                            deleted.add(checksum);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new StoreException("garbage collection failed while scanning blobs", e);
        }
        cleanupTemp(cutoff);
        return deleted.stream().sorted().toList();
    }

    /** 启动恢复：清理上次进程崩溃可能残留的 tmp 文件（当前活跃的除外）。 */
    public void recoverTempFiles() {
        cleanupTemp(Instant.MAX);
    }

    private void cleanupTemp(Instant cutoff) {
        try (Stream<Path> temps = Files.list(tmpDir)) {
            for (Path tmp : temps.filter(Files::isRegularFile).toList()) {
                if (activeTempFiles.contains(tmp.getFileName().toString())) {
                    continue;
                }
                if (cutoff != Instant.MAX) {
                    Instant modified = Files.getLastModifiedTime(tmp).toInstant();
                    if (modified.isAfter(cutoff)) {
                        continue;
                    }
                }
                deleteQuietly(tmp);
            }
        } catch (IOException e) {
            throw new StoreException("failed cleaning temp directory", e);
        }
    }

    /** 磁盘上现存的全部 blob 校验和（恢复/诊断用）。 */
    public Set<String> allBlobs() {
        Set<String> result = new HashSet<>();
        try (Stream<Path> buckets = Files.list(blobsDir)) {
            for (Path bucket : buckets.filter(Files::isDirectory).toList()) {
                try (Stream<Path> blobs = Files.list(bucket)) {
                    blobs.filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .forEach(result::add);
                }
            }
        } catch (IOException e) {
            throw new StoreException("failed listing blobs", e);
        }
        return result;
    }

    /** 已删除 blob 的数量（供测试断言回收效果）。 */
    public long blobCount() {
        return allBlobs().size();
    }

    /** 测试/诊断辅助：写入一个没有任何元数据引用的孤儿 blob，并返回其 checksum。 */
    public String writeOrphanBlob(InputStream data) {
        PutResult result = put(data, null);
        return result.checksum();
    }

    /** 测试辅助：把 blob 的最后修改时间回退到 age 之前，用于验证保留期边界。 */
    public void ageBlob(String checksum, Duration age) {
        try {
            Files.setLastModifiedTime(blobPath(checksum),
                    java.nio.file.attribute.FileTime.from(Instant.now().minus(age)));
        } catch (IOException e) {
            throw new StoreException("cannot age blob: " + checksum, e);
        }
    }

    private Path blobPath(String checksum) {
        return blobsDir.resolve(checksum.substring(0, 2)).resolve(checksum);
    }

    private static boolean isValidBlobPath(String checksum) {
        return checksum != null && checksum.length() == 64
                && checksum.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程，下次 GC / 重启恢复会再次尝试。
        }
    }

    /** 写入结果：实际内容的校验和与字节数。 */
    public record PutResult(String checksum, long size) {
    }

}
