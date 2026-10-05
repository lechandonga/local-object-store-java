package com.github.lechandonga.localobjectstorejava.tests.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import com.github.lechandonga.localobjectstorejava.config.StoreProperties;
import com.github.lechandonga.localobjectstorejava.store.ObjectStore;

public final class TestStores {

    private TestStores() {
    }

    /** 使用独立临时目录的存储实例，retention 为 0 表示所有无引用内容立即可回收。 */
    public static ObjectStore newStore() {
        return newStore(Duration.ZERO);
    }

    public static ObjectStore newStore(Duration retention) {
        try {
            Path root = Files.createTempDirectory("object-store-test-");
            StoreProperties props = new StoreProperties();
            props.setRoot(root);
            props.setRetention(retention);
            ObjectStore store = new ObjectStore(props);
            store.init();
            return store;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 指向已有目录的全新存储实例，用于模拟进程重启后的恢复。 */
    public static ObjectStore reopen(ObjectStore previous) {
        return reopen(previous, Duration.ZERO);
    }

    /** 指向已有目录的全新存储实例，可覆盖保留期，用于模拟重启或跨保留期回收。 */
    public static ObjectStore reopen(ObjectStore previous, Duration retention) {
        StoreProperties props = new StoreProperties();
        props.setRoot(previous.root());
        props.setRetention(retention);
        ObjectStore store = new ObjectStore(props);
        store.init();
        return store;
    }

    public static byte[] bytes(String content) {
        return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static InputStream stream(String content) {
        return new java.io.ByteArrayInputStream(bytes(content));
    }

    public static InputStream stream(byte[] content) {
        return new java.io.ByteArrayInputStream(content);
    }

    public static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] readAll(ObjectStore.ReadResult r) throws IOException {
        return Files.readAllBytes(r.path);
    }
}
