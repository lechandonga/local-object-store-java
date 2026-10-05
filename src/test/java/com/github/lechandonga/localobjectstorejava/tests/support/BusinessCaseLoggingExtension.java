package com.github.lechandonga.localobjectstorejava.tests.support;

import java.util.Optional;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试日志扩展：按业务类（@Nested 分组）输出用例开始与结果，
 * 让测试日志可以清晰看出覆盖了哪些业务场景、各用例通过与否。
 */
public class BusinessCaseLoggingExtension implements TestWatcher, BeforeAllCallback, AfterAllCallback {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-CASE");

    private static String businessGroup(ExtensionContext context) {
        Optional<ExtensionContext> nested = context.getParent();
        return nested.map(ExtensionContext::getDisplayName).orElse("根用例集");
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (context.getTestClass().isPresent()
                && context.getTestClass().get().isMemberClass()) {
            log.info("==== 业务场景开始: {} ====", context.getDisplayName());
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (context.getTestClass().isPresent()
                && context.getTestClass().get().isMemberClass()) {
            log.info("==== 业务场景结束: {} ====", context.getDisplayName());
        }
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        log.info("[用例结果][通过] 业务={} 用例={}", businessGroup(context), context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.error("[用例结果][失败] 业务={} 用例={} 原因={}", businessGroup(context),
                context.getDisplayName(), cause.toString());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        log.warn("[用例结果][中止] 业务={} 用例={} 原因={}", businessGroup(context),
                context.getDisplayName(), cause.toString());
    }
}
