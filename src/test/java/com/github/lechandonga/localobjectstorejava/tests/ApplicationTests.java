package com.github.lechandonga.localobjectstorejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 冒烟：Spring 上下文启动并通过 HTTP 完成一次上传/下载闭环。 */
class ApplicationTests extends IntegrationTestConfig {

    ApplicationTests() {
        super("应用启动冒烟");
    }

    @Test
    void uploadAndDownloadRoundTrip() {
        byte[] content = "hello-local-object-store".getBytes();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Content-Checksum", sha256(content));

        ResponseEntity<String> put = rest.exchange(url("/objects/smoke-hello"),
                HttpMethod.PUT, new HttpEntity<>(content, headers), String.class);
        assertEquals(200, put.getStatusCode().value());

        ResponseEntity<byte[]> got = rest.getForEntity(url("/objects/smoke-hello"), byte[].class);
        assertEquals(200, got.getStatusCode().value());
        assertArrayEquals(content, got.getBody());
        assertEquals(sha256(content), got.getHeaders().getFirst("X-Content-Checksum"));
    }
}
