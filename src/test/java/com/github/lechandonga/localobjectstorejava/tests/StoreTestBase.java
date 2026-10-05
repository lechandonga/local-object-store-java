package com.github.lechandonga.localobjectstorejava.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 测试基类：提供 HTTP 客户端、SHA-256 工具以及"按业务分组"的用例结果日志。
 * 子类构造时传入业务名称，每个用例执行后在控制台打印
 * {@code [业务名称] [OK]/[X] 用例名}，mvn test 输出即可看出各业务覆盖了哪些用例及结果。
 */
public abstract class StoreTestBase {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    private final String feature;

    protected StoreTestBase(String feature) {
        this.feature = feature;
    }

    @BeforeEach
    void logStart(TestInfo info) {
        System.out.printf(">>>>>> [%s] 开始用例: %s%n", feature, displayName(info));
    }

    @AfterEach
    void logEnd(TestInfo info) {
        System.out.printf("<<<<<< [%s] [OK] 用例结束: %s%n", feature, displayName(info));
    }

    private String displayName(TestInfo info) {
        return info.getTestMethod().map(m -> m.getName()).orElseGet(info::getDisplayName);
    }

    protected static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected byte[] bytes(int size, byte fill) {
        byte[] data = new byte[size];
        java.util.Random random = new java.util.Random((long) fill * 1_000_003L + size);
        for (int i = 0; i < size; i++) {
            data[i] = (byte) random.nextInt(256);
        }
        data[0] = fill;
        return data;
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
