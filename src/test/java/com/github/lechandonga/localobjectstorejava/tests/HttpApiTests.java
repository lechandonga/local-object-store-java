package com.github.lechandonga.localobjectstorejava.tests;

import static com.github.lechandonga.localobjectstorejava.tests.support.TestStores.bytes;
import static com.github.lechandonga.localobjectstorejava.tests.support.TestStores.sha256Hex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.localobjectstorejava.store.ObjectStore;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpApiTests {

    @LocalServerPort
    int port;

    @Autowired
    ObjectStore store;

    private final TestRestTemplate rest = new TestRestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String suffix) {
        return "http://localhost:" + port + "/objects/" + suffix;
    }

    private HttpHeaders headersWith(String checksum) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Checksum-SHA256", checksum);
        return headers;
    }

    private ResponseEntity<String> upload(String key, byte[] data) {
        return rest.exchange(url(key), HttpMethod.POST,
                new HttpEntity<>(data, headersWith(sha256Hex(data))), String.class);
    }

    @Nested
    @DisplayName("业务7: HTTP 全链路接口")
    class FullLifecycle {

        @Test
        @DisplayName("上传成功响应含版本号与校验和，重复内容第二次上传标记 deduplicated")
        void uploadAndDedup() throws Exception {
            byte[] data = bytes("http-upload-content-" + UUID.randomUUID());
            ResponseEntity<String> resp = upload("http-dedup/summary", data);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            JsonNode body = mapper.readTree(resp.getBody());
            assertEquals(1, body.get("version").asLong());
            assertEquals(sha256Hex(data), body.get("checksum").asText());

            ResponseEntity<String> again = upload("http-dedup/copy", data);
            assertEquals(true, mapper.readTree(again.getBody()).get("deduplicated").asBoolean());
        }

        @Test
        @DisplayName("下载返回内容、版本头与校验和头，且与上传一致")
        void downloadMatchesUpload() {
            String unique = UUID.randomUUID().toString();
            byte[] data = bytes("http-download-" + unique);
            upload("http-download/" + unique, data);
            ResponseEntity<byte[]> resp = rest.getForEntity(url("http-download/" + unique),
                    byte[].class);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertArrayEquals(data, resp.getBody());
            assertEquals(sha256Hex(data), resp.getHeaders().getFirst("X-Checksum-SHA256"));
            assertEquals("1", resp.getHeaders().getFirst("X-Object-Version"));
        }

        @Test
        @DisplayName("错误校验和返回 400 CHECKSUM_MISMATCH，从未存在返回 404 NOT_FOUND")
        void checksumMismatchAndNotFound() {
            ResponseEntity<String> bad = rest.exchange(
                    url("bad-checksum-" + UUID.randomUUID()), HttpMethod.POST,
                    new HttpEntity<>(bytes("real-body"), headersWith(sha256Hex(bytes("different")))),
                    String.class);
            assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
            assertEquals(true, bad.getBody().contains("CHECKSUM_MISMATCH"));

            ResponseEntity<String> missing = rest.getForEntity(
                    url("never-existed-" + UUID.randomUUID()), String.class);
            assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
            assertEquals(true, missing.getBody().contains("NOT_FOUND"));
        }

        @Test
        @DisplayName("覆盖写、版本列表、回滚、快照与墓碑删除全链路可用")
        void versionsSnapshotDeleteAndGc() throws Exception {
            String unique = UUID.randomUUID().toString();
            String key = "lifecycle/" + unique;
            byte[] v1 = bytes("lifecycle-v1-" + unique);
            byte[] v2 = bytes("lifecycle-v2-" + unique);
            upload(key, v1);
            ResponseEntity<String> second = upload(key, v2);
            assertEquals(2, mapper.readTree(second.getBody()).get("version").asLong());

            ResponseEntity<String> versions = rest.getForEntity(url(key + "/versions"), String.class);
            assertEquals(2, mapper.readTree(versions.getBody()).size());

            ResponseEntity<String> snap = rest.exchange(url(key + "/snapshots"), HttpMethod.POST,
                    new HttpEntity<>(new byte[0]), String.class);
            String snapshotId = mapper.readTree(snap.getBody()).get("id").asText();
            assertNotNull(snapshotId);

            HttpHeaders jsonHeaders = new HttpHeaders();
            jsonHeaders.add("Content-Type", "application/json");
            ResponseEntity<String> rollback = rest.exchange(url(key + "/versions/rollback"),
                    HttpMethod.POST,
                    new HttpEntity<>("{\"version\":1}".getBytes(StandardCharsets.UTF_8), jsonHeaders),
                    String.class);
            assertEquals(3, mapper.readTree(rollback.getBody()).get("version").asLong());
            ResponseEntity<byte[]> current = rest.getForEntity(url(key), byte[].class);
            assertArrayEquals(v1, current.getBody());

            ResponseEntity<byte[]> snapContent = rest.getForEntity(
                    url(key + "/snapshots/" + snapshotId), byte[].class);
            assertArrayEquals(v2, snapContent.getBody());

            ResponseEntity<String> deleted = rest.exchange(url(key), HttpMethod.DELETE, null,
                    String.class);
            assertEquals(HttpStatus.OK, deleted.getStatusCode());
            ResponseEntity<String> gone = rest.getForEntity(url(key), String.class);
            assertEquals(HttpStatus.GONE, gone.getStatusCode());
            assertEquals(true, gone.getBody().contains("DELETED"));

            ResponseEntity<String> gc = rest.exchange("http://localhost:" + port + "/gc",
                    HttpMethod.POST, null, String.class);
            assertEquals(HttpStatus.OK, gc.getStatusCode());

            ResponseEntity<String> snapStillThere = rest.getForEntity(
                    url(key + "/snapshots/" + snapshotId), String.class);
            assertEquals(HttpStatus.OK, snapStillThere.getStatusCode(), "快照释放前内容必须仍可读");
        }
    }
}
