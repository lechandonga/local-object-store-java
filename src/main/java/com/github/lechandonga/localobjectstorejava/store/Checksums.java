package com.github.lechandonga.localobjectstorejava.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 校验和工具。 */
final class Checksums {

    private Checksums() {
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            updateDigest(digest, in);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static void updateDigest(MessageDigest digest, InputStream in) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
    }

    /** 归一化校验和表示：十六进制小写；同时兼容 "sha256:xxx" 前缀写法。 */
    static String normalize(String declared) {
        if (declared == null) {
            return null;
        }
        String value = declared.trim().toLowerCase();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        return value;
    }
}
