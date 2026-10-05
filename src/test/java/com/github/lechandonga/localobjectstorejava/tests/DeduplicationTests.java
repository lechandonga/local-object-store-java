package com.github.lechandonga.localobjectstorejava.tests;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务一：内容去重、幂等写入与校验和拒绝。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeduplicationTests extends IntegrationTestConfig {

    DeduplicationTests() {
        super("内容去重与幂等写入");
    }

    @Test
    @Order(1)
    void sameContentUploadedTwiceSharesOneBlob() throws Exception {
        byte[] content = bytes(4096, (byte) 11);
        String checksum = sha256(content);
        String key = "dedup-same-content";

        ResponseEntity<String> first = upload(key, content, checksum);
        ResponseEntity<String> second = upload(key, content, checksum);
        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, second.getStatusCode().value());

        long blobCount = countBlobs();
        assertEquals(1, blobsFor(checksum), "相同内容重复上传不得重复占用存储空间");

        ResponseEntity<byte[]> downloaded = rest.getForEntity(url("/objects/" + key), byte[].class);
        assertEquals(200, downloaded.getStatusCode().value());
        assertArrayEquals(content, downloaded.getBody(), "下载内容必须与上传一致");
        assertEquals(checksum, downloaded.getHeaders().getFirst("X-Content-Checksum"),
                "下载返回的校验和必须与上传一致");
    }

    @Test
    @Order(2)
    void sameContentUnderDifferentKeysStillDeduplicated() {
        byte[] content = bytes(2048, (byte) 23);
        String checksum = sha256(content);

        upload("dedup-key-a", content, checksum);
        upload("dedup-key-b", content, checksum);

        assertEquals(1, blobsFor(checksum), "不同 key 的相同内容也必须共享 blob");
    }

    @Test
    @Order(3)
    void wrongDeclaredChecksumIsRejectedWithDistinctError() {
        byte[] content = bytes(1024, (byte) 31);
        String wrongChecksum = sha256(bytes(1024, (byte) 32));

        ResponseEntity<String> response = upload("dedup-wrong-checksum", content, wrongChecksum);
        assertEquals(422, response.getStatusCode().value(), "校验和不符必须被拒绝（422）");
        String body = response.getBody();
        assertTrue(body != null && body.contains("CHECKSUM_MISMATCH"), "错误类型必须可区分");
        assertTrue(body.contains(sha256(content)), "错误体必须包含实际校验和");

        ResponseEntity<String> missing = rest.getForEntity(url("/objects/dedup-wrong-checksum"), String.class);
        assertEquals(404, missing.getStatusCode().value(), "校验失败不得产生任何对象或版本");
    }

    @Test
    @Order(4)
    void differentContentProducesDifferentBlobs() {
        byte[] first = bytes(512, (byte) 1);
        byte[] second = bytes(512, (byte) 2);
        assertNotEquals(sha256(first), sha256(second));

        upload("dedup-diff-a", first, sha256(first));
        upload("dedup-diff-b", second, sha256(second));

        ResponseEntity<byte[]> gotFirst = rest.getForEntity(url("/objects/dedup-diff-a"), byte[].class);
        ResponseEntity<byte[]> gotSecond = rest.getForEntity(url("/objects/dedup-diff-b"), byte[].class);
        assertArrayEquals(first, gotFirst.getBody());
        assertArrayEquals(second, gotSecond.getBody());
    }

    private ResponseEntity<String> upload(String key, byte[] content, String checksum) {
        HttpHeaders headers = new HttpHeaders();
        if (checksum != null) {
            headers.set("X-Content-Checksum", checksum);
        }
        return rest.exchange(url("/objects/" + key), HttpMethod.PUT,
                new HttpEntity<>(content, headers), String.class);
    }

    private long countBlobs() throws RuntimeException {
        try {
            Path blobs = DATA_DIR.resolve("blobs");
            if (!Files.isDirectory(blobs)) {
                return 0;
            }
            try (var paths = Files.walk(blobs)) {
                return paths.filter(Files::isRegularFile).count();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 统计指定校验和对应的 blob 文件数（全库共享目录，只断言目标内容的份数）。 */
    private long blobsFor(String checksum) {
        Path blob = DATA_DIR.resolve("blobs").resolve(checksum.substring(0, 2)).resolve(checksum);
        return Files.isRegularFile(blob) ? 1 : 0;
    }
}
