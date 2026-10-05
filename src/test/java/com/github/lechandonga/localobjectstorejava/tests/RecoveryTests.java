package com.github.lechandonga.localobjectstorejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.localobjectstorejava.store.ContentCorruptedException;
import com.github.lechandonga.localobjectstorejava.store.ContentStore;
import com.github.lechandonga.localobjectstorejava.store.MetadataStore;
import com.github.lechandonga.localobjectstorejava.store.ObjectStoreService;
import com.github.lechandonga.localobjectstorejava.store.StorageProperties;
import com.github.lechandonga.localobjectstorejava.store.VersionInfo;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务六：写入/回收中途崩溃后的重启自洽、孤儿回收、损坏内容可区分报错。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecoveryTests extends IntegrationTestConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ObjectStoreService service;

    @Autowired
    private ContentStore contentStore;

    RecoveryTests() {
        super("中断恢复与数据自洽");
    }

    @Test
    @Order(1)
    void crashDuringUploadLeavesOnlyTempWhichRecoveryRemoves() throws Exception {
        Path tmpDir = DATA_DIR.resolve("tmp");
        Files.createDirectories(tmpDir);
        Path staged = tmpDir.resolve("upload-crashed.tmp");
        Files.write(staged, bytes(500, (byte) 51));

        assertEquals(1, countRegular(tmpDir), "模拟写入中途退出：留下了半截临时文件");

        service.recover();

        assertEquals(0, countRegular(tmpDir), "重启后临时残留必须被清理，不得产生半截对象");
        ResponseEntity<String> missing = rest.getForEntity(
                url("/objects/recovery-half-written"), String.class);
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test
    @Order(2)
    void crashDuringMetadataWriteLeavesNoHalfJson() throws Exception {
        String key = "recovery-meta-crash";
        byte[] content = bytes(128, (byte) 52);
        put(key, content);

        Path staged = DATA_DIR.resolve("meta").resolve("keys").resolve("meta-crashed.tmp");
        Files.writeString(staged, "{ this is not a finished json", StandardCharsets.UTF_8);

        service.recover();

        byte[] got = rest.getForEntity(url("/objects/" + key), byte[].class).getBody();
        assertArrayEquals(content, got, "元数据暂存崩溃不影响已提交的元数据，对象仍可读");
        assertFalse(Files.exists(staged), "半截元数据暂存文件必须被清理");
    }

    @Test
    @Order(3)
    void freshServiceInstanceOnSameDiskIsSelfConsistent() {
        String key = "recovery-restart";
        byte[] content = bytes(64, (byte) 53);
        service.put(key, new ByteArrayInputStream(content), sha256(content));

        StorageProperties props = new StorageProperties();
        props.setRoot(DATA_DIR.toString());
        ContentStore restartedContent = new ContentStore(props);
        MetadataStore restartedMeta = new MetadataStore(props);
        ObjectStoreService restarted = new ObjectStoreService(restartedContent, restartedMeta, props);
        restarted.recover();

        try (var read = restarted.read(key)) {
            assertArrayEquals(content, read.stream().readAllBytes(), "重启后元数据与内容必须自洽");
            assertEquals(sha256(content), read.checksum());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertEquals(1, restarted.listVersions(key).size());
        VersionInfo current = restarted.listVersions(key).get(0);
        assertEquals(sha256(content), current.checksum());
    }

    @Test
    @Order(4)
    void orphanedBlobFromCrashBeforeMetadataCommitIsEventuallyReclaimed() {
        byte[] orphan = bytes(96, (byte) 54);
        String checksum = contentStore.writeOrphanBlob(new ByteArrayInputStream(orphan));
        assertTrue(contentStore.exists(checksum),
                "blob 已提交但元数据未提交（崩溃窗口）的内容成为孤儿但仍在盘上");

        ResponseEntity<String> gc = rest.postForEntity(
                url("/gc?retentionSeconds=0"), null, String.class);
        assertTrue(gc.getBody().contains(checksum), "孤儿数据必须能被 GC 清掉，不会永久残留");
        assertFalse(contentStore.exists(checksum));
    }

    @Test
    @Order(5)
    void corruptedBlobProducesDistinctErrorInsteadOfWrongData() throws Exception {
        String key = "recovery-corrupt";
        byte[] content = bytes(77, (byte) 55);
        service.put(key, new ByteArrayInputStream(content), sha256(content));

        VersionInfo current = service.listVersions(key).get(0);
        Path blob = DATA_DIR.resolve("blobs")
                .resolve(current.checksum().substring(0, 2))
                .resolve(current.checksum());
        byte[] tampered = content.clone();
        tampered[0] ^= (byte) 0xFF;
        Files.write(blob, tampered);

        assertThrows(ContentCorruptedException.class, () -> service.read(key),
                "读到损坏内容必须抛出可区分的损坏错误，而不是返回错误数据");

        ResponseEntity<String> http = rest.getForEntity(url("/objects/" + key), String.class);
        assertEquals(409, http.getStatusCode().value(), "损坏内容通过 HTTP 返回 409 CONTENT_CORRUPTED");
        assertTrue(http.getBody().contains("CONTENT_CORRUPTED"));
    }

    private void put(String key, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Content-Checksum", sha256(content));
        ResponseEntity<String> response = rest.exchange(url("/objects/" + key), HttpMethod.PUT,
                new HttpEntity<>(content, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
    }

    private long countRegular(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
