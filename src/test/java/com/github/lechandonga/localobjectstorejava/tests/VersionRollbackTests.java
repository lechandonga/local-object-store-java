package com.github.lechandonga.localobjectstorejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务二：覆盖版本、墓碑删除、区分删除/不存在、回滚可见性。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VersionRollbackTests extends IntegrationTestConfig {

    VersionRollbackTests() {
        super("版本/墓碑/回滚");
    }

    @Test
    @Order(1)
    void overwriteCreatesListableReadableVersions() {
        String key = "version-overwrite";
        byte[] v1 = bytes(100, (byte) 1);
        byte[] v2 = bytes(200, (byte) 2);
        byte[] v3 = bytes(300, (byte) 3);

        upload(key, v1);
        upload(key, v2);
        upload(key, v3);

        JsonNode versions = listVersions(key);
        assertEquals(3, versions.path("versions").size(), "覆盖写必须产生新版本");

        assertArrayEquals(v1, download(key, 1));
        assertArrayEquals(v2, download(key, 2));
        assertArrayEquals(v3, download(key, 3), "每个历史版本都必须可读取");
        assertArrayEquals(v3, rest.getForEntity(url("/objects/" + key), byte[].class).getBody(),
                "当前版本应为最新版本");
    }

    @Test
    @Order(2)
    void deletedObjectIs410AndNeverExistedIs404() {
        String key = "version-tombstone";
        upload(key, bytes(50, (byte) 9));

        ResponseEntity<String> deleted = rest.exchange(url("/objects/" + key),
                HttpMethod.DELETE, null, String.class);
        assertEquals(200, deleted.getStatusCode().value());

        ResponseEntity<String> gone = rest.getForEntity(url("/objects/" + key), String.class);
        assertEquals(410, gone.getStatusCode().value(), "已删除对象必须返回 410 墓碑");
        assertTrue(gone.getBody().contains("OBJECT_DELETED"));

        ResponseEntity<String> missing = rest.getForEntity(url("/objects/version-never-existed"), String.class);
        assertEquals(404, missing.getStatusCode().value(), "从未存在的对象必须返回 404");
        assertTrue(missing.getBody().contains("OBJECT_NOT_FOUND"));

        JsonNode versions = listVersions(key);
        assertEquals(2, versions.path("versions").size(), "删除后历史版本仍然保留可列举");
        assertTrue(versions.path("versions").get(1).path("tombstone").asBoolean());

        assertArrayEquals(bytes(50, (byte) 9), download(key, 1), "删除后历史版本仍可读取");
    }

    @Test
    @Order(3)
    void rollbackRestoresOldVisibilityWithoutLosingHistory() {
        String key = "version-rollback";
        byte[] v1 = bytes(10, (byte) 4);
        byte[] v2 = bytes(20, (byte) 5);
        upload(key, v1);
        upload(key, v2);

        ResponseEntity<String> rollback = rest.postForEntity(
                url("/objects/" + key + "/versions/1/rollback"), null, String.class);
        assertEquals(200, rollback.getStatusCode().value());

        assertArrayEquals(v1, rest.getForEntity(url("/objects/" + key), byte[].class).getBody(),
                "回滚后当前可见版本必须是 v1");

        assertArrayEquals(v2, download(key, 2), "被回滚的 v2 内容仍可按版本读取");

        JsonNode versions = listVersions(key);
        assertEquals(2, versions.path("versions").size(), "回滚不新增/删除版本，只移动当前指针");
    }

    @Test
    @Order(4)
    void overwriteAfterDeleteAndRollbackAcrossTombstone() {
        String key = "version-revive";
        byte[] v1 = bytes(7, (byte) 6);
        byte[] v3 = bytes(9, (byte) 8);
        upload(key, v1);
        rest.exchange(url("/objects/" + key), HttpMethod.DELETE, null, String.class);
        upload(key, v3);

        assertArrayEquals(v3, rest.getForEntity(url("/objects/" + key), byte[].class).getBody());

        rest.postForEntity(url("/objects/" + key + "/versions/1/rollback"), null, String.class);
        assertArrayEquals(v1, rest.getForEntity(url("/objects/" + key), byte[].class).getBody(),
                "经历删除与重新上传后，仍可回滚到最初版本");

        ResponseEntity<String> tombstoneRollback = rest.postForEntity(
                url("/objects/" + key + "/versions/2/rollback"), null, String.class);
        assertEquals(400, tombstoneRollback.getStatusCode().value(), "不允许回滚到墓碑版本");
    }

    private void upload(String key, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Content-Checksum", sha256(content));
        ResponseEntity<String> response = rest.exchange(url("/objects/" + key), HttpMethod.PUT,
                new HttpEntity<>(content, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
    }

    private byte[] download(String key, long version) {
        return rest.getForEntity(url("/objects/" + key + "/versions/" + version), byte[].class).getBody();
    }

    private JsonNode listVersions(String key) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(rest.getForEntity(url("/objects/" + key + "/versions"), String.class).getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
