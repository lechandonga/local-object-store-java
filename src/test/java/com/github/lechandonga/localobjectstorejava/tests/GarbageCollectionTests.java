package com.github.lechandonga.localobjectstorejava.tests;

import com.github.lechandonga.localobjectstorejava.store.ContentStore;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务四：回收判定边界——引用保护、保留期、孤儿清理。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GarbageCollectionTests extends IntegrationTestConfig {

    @Autowired
    private ContentStore contentStore;

    GarbageCollectionTests() {
        super("空间回收边界");
    }

    @Test
    @Order(1)
    void unreferencedBlobOlderThanRetentionIsReclaimed() {
        byte[] orphan = bytes(256, (byte) 41);
        String checksum = contentStore.writeOrphanBlob(new ByteArrayInputStream(orphan));
        assertTrue(contentStore.exists(checksum));

        contentStore.ageBlob(checksum, Duration.ofMinutes(10));
        ResponseEntity<String> response = rest.postForEntity(
                url("/gc?retentionSeconds=300"), null, String.class);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains(checksum), "超保留期的孤儿 blob 必须被回收");
        assertFalse(contentStore.exists(checksum));
    }

    @Test
    @Order(2)
    void freshOrphanIsKeptWithinRetentionWindow() {
        byte[] orphan = bytes(257, (byte) 42);
        String checksum = contentStore.writeOrphanBlob(new ByteArrayInputStream(orphan));

        ResponseEntity<String> response = rest.postForEntity(
                url("/gc?retentionSeconds=300"), null, String.class);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(contentStore.exists(checksum), "未超保留期的无引用内容必须保留");
        assertFalse(response.getBody().contains(checksum));

        contentStore.ageBlob(checksum, Duration.ofSeconds(301));
        rest.postForEntity(url("/gc?retentionSeconds=300"), null, String.class);
        assertFalse(contentStore.exists(checksum), "超过保留期边界后必须被回收");
    }

    @Test
    @Order(3)
    void referencedBlobIsNeverReclaimedEvenWhenOld() {
        byte[] content = bytes(258, (byte) 43);
        String checksum = sha256(content);
        put("gc-referenced", content, checksum);
        contentStore.ageBlob(checksum, Duration.ofDays(1));

        rest.postForEntity(url("/gc?retentionSeconds=1"), null, String.class);
        assertTrue(contentStore.exists(checksum), "被存活版本引用的 blob 无论多旧都不得回收");

        rest.exchange(url("/objects/gc-referenced"), HttpMethod.DELETE, null, String.class);
        rest.postForEntity(url("/gc?retentionSeconds=1"), null, String.class);
        assertTrue(contentStore.exists(checksum), "墓碑只移动当前指针，历史版本引用仍保护 blob");
    }

    private void put(String key, byte[] content, String checksum) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Content-Checksum", checksum);
        ResponseEntity<String> response = rest.exchange(url("/objects/" + key), HttpMethod.PUT,
                new HttpEntity<>(content, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
    }
}
