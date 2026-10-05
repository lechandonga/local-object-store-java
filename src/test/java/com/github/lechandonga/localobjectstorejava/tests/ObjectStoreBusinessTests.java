package com.github.lechandonga.localobjectstorejava.tests;

import static com.github.lechandonga.localobjectstorejava.tests.support.TestStores.bytes;
import static com.github.lechandonga.localobjectstorejava.tests.support.TestStores.newStore;
import static com.github.lechandonga.localobjectstorejava.tests.support.TestStores.readAll;
import static com.github.lechandonga.localobjectstorejava.tests.support.TestStores.reopen;
import static com.github.lechandonga.localobjectstorejava.tests.support.TestStores.sha256Hex;
import static com.github.lechandonga.localobjectstorejava.tests.support.TestStores.stream;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.github.lechandonga.localobjectstorejava.store.Metadata;
import com.github.lechandonga.localobjectstorejava.store.ObjectStore;
import com.github.lechandonga.localobjectstorejava.store.StoreException;

/**
 * 本地对象存储核心业务测试：
 * 覆盖内容去重/幂等、版本与回滚、快照引用、回收边界、并发一致性、中断恢复与损坏检测。
 */
class ObjectStoreBusinessTests {

    private static ObjectStore.UploadResult put(ObjectStore store, String key, String content) {
        byte[] data = bytes(content);
        return store.put(key, sha256Hex(data), stream(data));
    }

    private static long countBlobFiles(Path root) throws Exception {
        Path blobs = root.resolve("blobs");
        if (!Files.isDirectory(blobs)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(blobs)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .count();
        }
    }

    @Nested
    @DisplayName("业务1: 内容去重与幂等写入")
    class DeduplicationTests {

        @Test
        @DisplayName("相同内容重复上传只保留一份 blob，且幂等返回一致")
        void sameContentDeduplicatedAcrossKeys() throws Exception {
            ObjectStore store = newStore();
            byte[] data = bytes("hello-dedup");

            var r1 = store.put("k1", sha256Hex(data), stream(data));
            var r2 = store.put("k2", sha256Hex(data), stream(data));
            var r3 = store.put("k1", sha256Hex(data), stream(data));

            assertEquals(1, countBlobFiles(store.root()));
            assertEquals(r1.checksum, r2.checksum);
            assertFalse(r1.deduplicated);
            assertTrue(r2.deduplicated);
            assertArrayEquals(data, readAll(store.get("k1")));
            assertArrayEquals(data, readAll(store.get("k2")));
            assertEquals(2, r3.version);
        }

        @Test
        @DisplayName("校验和不匹配返回 CHECKSUM_MISMATCH，且不产生任何 blob")
        void checksumMismatchRejected() throws Exception {
            ObjectStore store = newStore();
            byte[] data = bytes("actual");
            String wrong = sha256Hex(bytes("something-else"));

            StoreException e = assertThrows(StoreException.class,
                    () -> store.put("k1", wrong, stream(data)));
            assertEquals("CHECKSUM_MISMATCH", e.getErrorCode());
            assertEquals(0, countBlobFiles(store.root()));
            assertThrows(StoreException.class, () -> store.get("k1"));
        }

        @Test
        @DisplayName("缺少校验和返回 CHECKSUM_REQUIRED，非法格式返回 CHECKSUM_INVALID")
        void checksumRequiredAndValidated() throws Exception {
            ObjectStore store = newStore();
            StoreException missing = assertThrows(StoreException.class,
                    () -> store.put("k", null, stream("x")));
            assertEquals("CHECKSUM_REQUIRED", missing.getErrorCode());
            StoreException invalid = assertThrows(StoreException.class,
                    () -> store.put("k", "abc", stream("x")));
            assertEquals("CHECKSUM_INVALID", invalid.getErrorCode());
        }

        @Test
        @DisplayName("并发上传同一内容只落一份 blob，所有结果校验和一致")
        void concurrentSameContentSingleBlob() throws Exception {
            ObjectStore store = newStore();
            int threads = 16;
            byte[] data = bytes("concurrent-content-" + UUID.randomUUID());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = "k" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return store.put(key, sha256Hex(data), stream(data)).checksum;
                }));
            }
            start.countDown();
            for (Future<String> f : futures) {
                assertEquals(sha256Hex(data), f.get());
            }
            pool.shutdown();
            assertEquals(1, countBlobFiles(store.root()));
            for (int i = 0; i < threads; i++) {
                assertArrayEquals(data, readAll(store.get("k" + i)));
            }
        }
    }

    @Nested
    @DisplayName("业务2: 版本、回滚与删除墓碑")
    class VersionRollbackTests {

        @Test
        @DisplayName("覆盖写产生可列举可读版本，回滚后历史版本仍可读")
        void overwriteVersionsAndRollback() throws Exception {
            ObjectStore store = newStore();
            var v1 = put(store, "doc", "content-v1");
            var v2 = put(store, "doc", "content-v2");
            var v3 = put(store, "doc", "content-v3");

            assertEquals("content-v3", new String(readAll(store.get("doc"))));
            List<Metadata.Version> versions = store.listVersions("doc");
            assertEquals(3, versions.size());

            long restored = store.rollback("doc", v1.version);
            assertEquals(4, restored);
            assertEquals("content-v1", new String(readAll(store.get("doc"))));
            assertEquals("content-v2", new String(readAll(store.getVersion("doc", v2.version))));
            assertEquals("content-v3", new String(readAll(store.getVersion("doc", v3.version))));
            assertEquals(4, store.listVersions("doc").size());
        }

        @Test
        @DisplayName("删除为墓碑：已删除返回 DELETED，从未存在返回 NOT_FOUND，可回滚恢复")
        void tombstoneDistinguishesDeletedFromMissingAndRollbackRestores() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "alive");

            long tombstone = store.delete("doc");
            StoreException deleted = assertThrows(StoreException.class, () -> store.get("doc"));
            assertEquals("DELETED", deleted.getErrorCode());

            StoreException notFound = assertThrows(StoreException.class, () -> store.get("never"));
            assertEquals("NOT_FOUND", notFound.getErrorCode());

            StoreException tombstoneVersion = assertThrows(StoreException.class,
                    () -> store.getVersion("doc", tombstone));
            assertEquals("DELETED", tombstoneVersion.getErrorCode());

            long restored = store.rollback("doc", 1);
            assertEquals("alive", new String(readAll(store.get("doc"))));
            assertEquals(3, restored);
        }

        @Test
        @DisplayName("删除后重新上传再回滚到墓碑版本，当前状态重新变为已删除")
        void rollbackToTombstoneMakesDeletedAgain() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "v1");
            long tombstone = store.delete("doc");
            put(store, "doc", "v2");

            long rolledBack = store.rollback("doc", tombstone);
            assertEquals(4, rolledBack);
            StoreException e = assertThrows(StoreException.class, () -> store.get("doc"));
            assertEquals("DELETED", e.getErrorCode());
            assertEquals("v2", new String(readAll(store.getVersion("doc", 3))));
        }

        @Test
        @DisplayName("回滚不存在的版本返回 VERSION_NOT_FOUND")
        void rollbackUnknownVersion() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "v1");
            StoreException e = assertThrows(StoreException.class, () -> store.rollback("doc", 99));
            assertEquals("VERSION_NOT_FOUND", e.getErrorCode());
        }
    }

    @Nested
    @DisplayName("业务3: 快照引用保护")
    class SnapshotTests {

        @Test
        @DisplayName("快照固定的版本在快照释放前不被回收，释放后可被回收")
        void snapshotPinsBlobUntilReleased() throws Exception {
            ObjectStore store = newStore();
            byte[] v1 = bytes("snap-v1");
            var uploaded = store.put("doc", sha256Hex(v1), stream(v1));
            Metadata.Snapshot snap = store.createSnapshot("doc");
            assertEquals(uploaded.checksum, snap.checksum);

            put(store, "doc", "snap-v2");

            var gc1 = store.garbageCollect();
            assertEquals(0, gc1.blobsDeleted, "快照引用的 v1 blob 不得被回收");
            assertEquals("snap-v1", new String(readAll(store.getSnapshot("doc", snap.id))));

            store.releaseSnapshot("doc", snap.id);
            var gc2 = store.garbageCollect();
            assertEquals(1, gc2.blobsDeleted, "快照释放后 v1 blob 应被回收");
            StoreException e = assertThrows(StoreException.class,
                    () -> store.getSnapshot("doc", snap.id));
            assertEquals("SNAPSHOT_NOT_FOUND", e.getErrorCode());
        }

        @Test
        @DisplayName("删除对象后快照仍可读，快照版本记录也被保留")
        void snapshotSurvivesDelete() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "keep-me");
            Metadata.Snapshot snap = store.createSnapshot("doc");
            store.delete("doc");

            assertThrows(StoreException.class, () -> store.get("doc"));
            assertEquals("keep-me", new String(readAll(store.getSnapshot("doc", snap.id))));
            assertEquals(0, store.garbageCollect().blobsDeleted);
        }

        @Test
        @DisplayName("对已删除对象创建快照返回 DELETED")
        void snapshotOnDeletedRejected() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "x");
            store.delete("doc");
            StoreException e = assertThrows(StoreException.class, () -> store.createSnapshot("doc"));
            assertEquals("DELETED", e.getErrorCode());
        }
    }

    @Nested
    @DisplayName("业务4: 空间回收边界")
    class GarbageCollectionTests {

        @Test
        @DisplayName("保留期内的无引用 blob 不回收，超过保留期才回收")
        void retentionBoundary() throws Exception {
            ObjectStore store = newStore(Duration.ofMinutes(10));
            byte[] v1 = bytes("retained-v1");
            store.put("doc", sha256Hex(v1), stream(v1));
            put(store, "doc", "retained-v2");

            assertEquals(0, store.garbageCollect().blobsDeleted, "保留期内旧 blob 不得删除");

            ObjectStore expired = reopen(store);
            assertEquals(1, expired.garbageCollect().blobsDeleted, "超过保留期的旧 blob 应删除");
            assertEquals("retained-v2", new String(readAll(expired.get("doc"))));
        }

        @Test
        @DisplayName("GC 裁剪过期非当前、非快照版本记录，但保留当前与被引用版本")
        void prunesOldVersionsOnly() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "a");
            put(store, "doc", "b");
            put(store, "doc", "c");

            store.garbageCollect();
            assertEquals(1, store.listVersions("doc").size(), "旧版本记录应被裁剪");
            assertEquals("c", new String(readAll(store.get("doc"))));
        }

        @Test
        @DisplayName("全删后 GC 清理孤儿 blob 与元数据，且数据自洽")
        void gcAfterDeleteReclaimsOrphansAndMetadata() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "only-version");
            store.delete("doc");

            var result = store.garbageCollect();
            assertEquals(1, result.blobsDeleted);
            assertEquals(1, result.metadataDeleted, "只剩墓碑且过期后元数据文件应被删除");
            StoreException e = assertThrows(StoreException.class, () -> store.get("doc"));
            assertEquals("NOT_FOUND", e.getErrorCode(), "元数据清理后语义等同从未存在");
        }

        @Test
        @DisplayName("多键共享 blob 在仍有一个键引用时不被回收")
        void sharedBlobProtectedByAnyReference() throws Exception {
            ObjectStore store = newStore();
            byte[] shared = bytes("shared-content");
            store.put("k1", sha256Hex(shared), stream(shared));
            store.put("k2", sha256Hex(shared), stream(shared));
            store.put("k1", sha256Hex(bytes("k1-new")), stream(bytes("k1-new")));

            assertEquals(0, store.garbageCollect().blobsDeleted, "k2 仍引用共享 blob");
            assertEquals("shared-content", new String(readAll(store.get("k2"))));
        }
    }

    @Nested
    @DisplayName("业务5: 并发一致性（读写/回滚/快照/GC 交错）")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发覆盖写与读取不出现半写数据，读到的内容与校验和始终匹配")
        void concurrentWritesAndReadsAlwaysConsistent() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "init");
            int writers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();

            for (int w = 0; w < writers; w++) {
                String content = "writer-" + w + "-" + UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 20; i++) {
                        byte[] data = bytes(content + ":" + i);
                        store.put("doc", sha256Hex(data), stream(data));
                    }
                    return null;
                }));
            }
            for (int r = 0; r < 2; r++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        ObjectStore.ReadResult read = store.get("doc");
                        byte[] data = readAll(read);
                        assertEquals(read.checksum, sha256Hex(data),
                                "下载内容的校验和必须与元数据一致，不得出现半写数据");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get();
            }
            pool.shutdown();
        }

        @Test
        @DisplayName("GC 与写入并发时，新提交引用的 blob 绝不被误删")
        void gcConcurrentWithPutsDoesNotDeleteLiveBlobs() throws Exception {
            ObjectStore store = newStore();
            put(store, "seed", "seed");
            int writers = 6;
            ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();

            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 30; i++) {
                    store.garbageCollect();
                }
                return null;
            }));
            for (int w = 0; w < writers; w++) {
                int id = w;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 30; i++) {
                        byte[] data = bytes("gc-conc-" + id + "-" + i + "-" + UUID.randomUUID());
                        store.put("k" + id, sha256Hex(data), stream(data));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get();
            }
            pool.shutdown();

            for (int w = 0; w < writers; w++) {
                ObjectStore.ReadResult r = store.get("k" + w);
                assertEquals(r.checksum, sha256Hex(readAll(r)), "GC 并发后所有当前版本仍完整可读");
            }
        }

        @Test
        @DisplayName("GC 与回滚/快照并发时不会让快照引用的内容失效")
        void gcConcurrentWithSnapshotAndRollback() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "v1");
            ExecutorService pool = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 30; i++) {
                    store.garbageCollect();
                }
                return null;
            }));
            List<Metadata.Snapshot> snaps = java.util.Collections.synchronizedList(new ArrayList<>());
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10; i++) {
                    put(store, "doc", "snap-cycle-" + i);
                    snaps.add(store.createSnapshot("doc"));
                }
                return null;
            }));
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10; i++) {
                    put(store, "doc", "roll-cycle-" + i);
                    try {
                        store.rollback("doc", 1);
                    } catch (StoreException e) {
                        if (!"VERSION_NOT_FOUND".equals(e.getErrorCode())) {
                            throw e;
                        }
                    }
                }
                return null;
            }));
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();
            for (Metadata.Snapshot snap : snaps) {
                ObjectStore.ReadResult r = store.getSnapshot("doc", snap.id);
                assertEquals(snap.checksum, sha256Hex(readAll(r)), "快照内容在并发 GC 后仍可用");
            }
        }
    }

    @Nested
    @DisplayName("业务6: 中断恢复与损坏检测")
    class RecoveryTests {

        @Test
        @DisplayName("重启后清理临时文件，已提交对象完整可读，版本/墓碑/快照语义保持")
        void recoverAfterRestart() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "persisted-v1");
            put(store, "doc", "persisted-v2");
            Metadata.Snapshot snap = store.createSnapshot("doc");
            put(store, "tomb", "soon-deleted");
            store.delete("tomb");

            Path tmpDir = store.root().resolve("blobs").resolve("tmp");
            Files.createDirectories(tmpDir);
            Files.createFile(tmpDir.resolve(UUID.randomUUID() + ".tmp"));

            ObjectStore reopened = reopen(store);
            assertEquals("persisted-v2", new String(readAll(reopened.get("doc"))));
            assertEquals("persisted-v1",
                    new String(readAll(reopened.getVersion("doc", 1))));
            assertEquals("persisted-v2",
                    new String(readAll(reopened.getSnapshot("doc", snap.id))));
            StoreException deleted = assertThrows(StoreException.class, () -> reopened.get("tomb"));
            assertEquals("DELETED", deleted.getErrorCode());
            try (Stream<Path> leftovers = Files.list(tmpDir)) {
                assertEquals(0, leftovers.count(), "启动恢复后不得残留临时文件");
            }
        }

        @Test
        @DisplayName("读到磁盘损坏的 blob 时返回 BLOB_CORRUPT，而不是错误数据")
        void corruptedBlobReportedDistinguishably() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "will-corrupt");
            ObjectStore.ReadResult r = store.get("doc");
            Files.writeString(r.path, "corrupted-bytes");

            StoreException e = assertThrows(StoreException.class, () -> store.get("doc"));
            assertEquals("BLOB_CORRUPT", e.getErrorCode());
        }

        @Test
        @DisplayName("元数据引用的 blob 缺失时返回 BLOB_MISSING（悬空引用可区分）")
        void missingBlobReportedAsDangling() throws Exception {
            ObjectStore store = newStore();
            put(store, "doc", "dangling");
            ObjectStore.ReadResult r = store.get("doc");
            try {
                Files.deleteIfExists(r.path);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            StoreException e = assertThrows(StoreException.class, () -> store.get("doc"));
            assertEquals("BLOB_MISSING", e.getErrorCode());
        }

        @Test
        @DisplayName("崩溃后遗留的孤儿 blob 超过保留期可被 GC 清掉，不会永远残留")
        void orphanBlobAfterCrashIsReclaimed() throws Exception {
            ObjectStore store = newStore();
            byte[] data = bytes("orphan-after-crash");
            store.put("doc", sha256Hex(data), stream(data));
            ObjectStore.ReadResult r = store.get("doc");

            Path copy = store.root().resolve("blobs").resolve("00").resolve("00")
                    .resolve("0".repeat(64));
            Files.createDirectories(copy.getParent());
            Files.copy(r.path, copy);
            Files.setLastModifiedTime(copy,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));

            assertEquals(1, store.garbageCollect().blobsDeleted);
            assertFalse(Files.exists(copy), "无引用的孤儿 blob 必须能被回收");
        }
    }
}
