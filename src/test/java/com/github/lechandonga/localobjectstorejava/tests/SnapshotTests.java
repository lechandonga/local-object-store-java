package com.github.lechandonga.localobjectstorejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务三：快照固定版本，快照存活期间引用内容不被回收，释放后可回收。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SnapshotTests extends IntegrationTestConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private com.github.lechandonga.localobjectstorejava.store.ContentStore contentStore;

    SnapshotTests() {
        super("快照引用保护");
    }

    @Test
    @Order(1)
    void snapshotPinsVersionsAcrossOverwriteDeleteAndRollback() throws Exception {
        String key = "snapshot-pinned";
        byte[] v1 = bytes(123, (byte) 2);
        byte[] v2 = bytes(456, (byte) 3);
        put(key, v1);

        String snapshotId = createSnapshot();

        put(key, v2);
        rest.exchange(url("/objects/" + key), HttpMethod.DELETE, null, String.class);

        ResponseEntity<byte[]> snapRead = rest.getForEntity(
                url("/snapshots/" + snapshotId + "/objects/" + key), byte[].class);
        assertEquals(200, snapRead.getStatusCode().value());
        assertArrayEquals(v1, snapRead.getBody(), "快照读取必须始终返回创建时固定的 v1 内容");

        ResponseEntity<String> gone = rest.getForEntity(url("/objects/" + key), String.class);
        assertEquals(410, gone.getStatusCode().value(), "当前视图已删除不影响快照视图");

        JsonNode snapshot = MAPPER.readTree(rest.getForEntity(
                url("/snapshots/" + snapshotId), String.class).getBody());
        JsonNode pinned = snapshot.path("refs").get(key);
        assertTrue(pinned != null && !pinned.isMissingNode(), "快照必须包含目标 key 的引用");
        assertEquals(1, pinned.path("version").asInt());
        assertEquals(sha256(v1), pinned.path("checksum").asText());
    }

    @Test
    @Order(2)
    void pinnedBlobSurvivesGcAndIsReclaimedAfterRelease() {
        String key = "snapshot-gc";
        byte[] v1 = bytes(321, (byte) 7);
        byte[] v2 = bytes(654, (byte) 8);
        put(key, v1);
        String snapshotId = createSnapshot();

        put(key, v2);

        JsonNode gc1 = gc();
        assertFalse(gc1.path("removedChecksums").toString().contains(sha256(v1)),
                "v1 被快照固定且仍在版本链中，不得回收");
        assertFalse(gc1.path("removedChecksums").toString().contains(sha256(v2)),
                "v2 是当前存活版本，不得回收");
        assertArrayEquals(v1, rest.getForEntity(
                url("/snapshots/" + snapshotId + "/objects/" + key), byte[].class).getBody());

        rest.exchange(url("/objects/" + key), HttpMethod.DELETE, null, String.class);
        JsonNode gc2 = gc();
        assertFalse(gc2.path("removedChecksums").toString().contains(sha256(v1)),
                "即使所有 key 版本变为墓碑，存活快照仍保护其引用的 blob");

        // 物理清除版本链：此后 v2 失去引用；v1 仍被存活快照固定。
        ResponseEntity<String> purge = rest.exchange(
                url("/objects/" + key + "?purgeHistory=true"), HttpMethod.DELETE, null, String.class);
        assertEquals(200, purge.getStatusCode().value());
        contentStore.ageBlob(sha256(v2), java.time.Duration.ofSeconds(5));
        JsonNode gcV2 = gc();
        assertTrue(gcV2.path("removedChecksums").toString().contains(sha256(v2)),
                "版本链清除后，v2 无任何引用且超期，必须可回收");
        assertArrayEquals(v1, rest.getForEntity(
                url("/snapshots/" + snapshotId + "/objects/" + key), byte[].class).getBody(),
                "v1 仍被快照固定，快照读取不受影响");

        ResponseEntity<String> release = rest.exchange(
                url("/snapshots/" + snapshotId), HttpMethod.DELETE, null, String.class);
        assertEquals(200, release.getStatusCode().value());

        // 刚写入的 blob mtime 仍在保留期窗口内，回退时间以验证"无引用且超期"的回收判定。
        contentStore.ageBlob(sha256(v1), java.time.Duration.ofSeconds(5));
        JsonNode gc3 = gc();
        assertTrue(gc3.path("removedChecksums").toString().contains(sha256(v1)),
                "快照释放后，失去引用的 v1 blob 必须可被回收");

        ResponseEntity<String> missingSnapshot = rest.getForEntity(
                url("/snapshots/" + snapshotId), String.class);
        assertEquals(404, missingSnapshot.getStatusCode().value(), "释放后快照不可再读");
    }

    private void put(String key, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Content-Checksum", sha256(content));
        ResponseEntity<String> response = rest.exchange(url("/objects/" + key), HttpMethod.PUT,
                new HttpEntity<>(content, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
    }

    private String createSnapshot() {
        ResponseEntity<String> response = rest.postForEntity(url("/snapshots"), null, String.class);
        assertEquals(200, response.getStatusCode().value());
        try {
            return MAPPER.readTree(response.getBody()).path("id").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode gc() {
        try {
            return MAPPER.readTree(rest.postForEntity(url("/gc?retentionSeconds=0"),
                    null, String.class).getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
