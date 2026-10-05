package com.github.lechandonga.localobjectstorejava.tests;

import com.github.lechandonga.localobjectstorejava.store.ContentStore;
import com.github.lechandonga.localobjectstorejava.store.ObjectStoreService;
import com.github.lechandonga.localobjectstorejava.store.VersionInfo;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务五：并发上传相同内容、GC 与进行中读取的并发安全。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConcurrencyTests extends IntegrationTestConfig {

    @Autowired
    private ContentStore contentStore;

    @Autowired
    private ObjectStoreService service;

    ConcurrencyTests() {
        super("并发安全");
    }

    @Test
    @Order(1)
    void concurrentUploadsOfSameContentProduceConsistentResult() throws Exception {
        int threads = 20;
        byte[] content = bytes(100_000, (byte) 17);
        String expectedChecksum = sha256(content);
        String key = "concurrency-same-upload";

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<VersionInfo>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.put(key, new ByteArrayInputStream(content), expectedChecksum);
            }));
        }
        start.countDown();

        for (Future<VersionInfo> future : futures) {
            VersionInfo info = future.get(30, TimeUnit.SECONDS);
            assertEquals(expectedChecksum, info.checksum(), "并发上传结果必须一致");
        }
        pool.shutdown();

        assertTrue(contentStore.exists(expectedChecksum),
                "并发上传的内容必须成功落盘");
        assertEquals(1, countBlobFiles(expectedChecksum),
                "20 个并发相同内容上传只能落一份 blob，不能重复落盘");
        assertEquals(threads, service.listVersions(key).size(),
                "每次幂等上传都产生一个新版本，版本号连续无冲突");

        byte[] downloaded = rest.getForEntity(url("/objects/" + key), byte[].class).getBody();
        assertEquals(expectedChecksum, sha256(downloaded), "最终读取内容与校验和必须与上传一致");
    }

    private long countBlobFiles(String checksum) throws Exception {
        Path blob = DATA_DIR.resolve("blobs").resolve(checksum.substring(0, 2)).resolve(checksum);
        return Files.isRegularFile(blob) ? 1 : 0;
    }

    @Test
    @Order(2)
    void concurrentGcDoesNotBreakInProgressReads() throws Exception {
        byte[] content = bytes(200_000, (byte) 27);
        String checksum = sha256(content);
        VersionInfo v = service.put("concurrency-gc-read",
                new ByteArrayInputStream(content), checksum);

        int readers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch readingGate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < readers; i++) {
            results.add(pool.submit(() -> {
                readingGate.await();
                try (var read = service.read("concurrency-gc-read")) {
                    byte[] buffer = new byte[4096];
                    long total = 0;
                    int n;
                    while ((n = read.stream().read(buffer)) != -1) {
                        total += n;
                        if (total < content.length / 2) {
                            Thread.sleep(2);
                        }
                    }
                    return total == content.length && read.checksum().equals(checksum);
                }
            }));
        }

        Future<?> gcFuture = pool.submit(() -> {
            assertDoesNotThrow(() -> readingGate.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) {
                service.garbageCollect(java.time.Duration.ZERO);
            }
        });

        readingGate.countDown();
        gcFuture.get(30, TimeUnit.SECONDS);
        for (Future<Boolean> result : results) {
            assertTrue(result.get(30, TimeUnit.SECONDS), "进行中的读取必须完整成功，不能被 GC 干扰");
        }
        pool.shutdown();

        assertEquals(checksum, service.read("concurrency-gc-read").checksum(),
                "GC 后在用对象仍然可读");
    }
}
