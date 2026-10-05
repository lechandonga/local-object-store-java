package com.github.lechandonga.localobjectstorejava.tests;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 全部集成测试共用一个随机端口的 Spring 上下文，存储目录使用构建目录下的临时目录，
 * 避免污染默认的 object-store-data。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestConfig extends StoreTestBase {

    protected static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("los-tests-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected IntegrationTestConfig(String feature) {
        super(feature);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("local-object-store.root", () -> DATA_DIR.toString());
        registry.add("local-object-store.retention-seconds", () -> "0");
    }
}
