package com.github.lechandonga.localobjectstorejava.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地存储相关配置。
 *
 * <pre>
 * local-object-store.root           存储根目录，默认 ./object-store-data
 * local-object-store.retention-seconds 回收保留期（秒），超过该时长且无引用的内容才可回收，默认 300
 * </pre>
 */
@ConfigurationProperties(prefix = "local-object-store")
public class StorageProperties {

    private String root = "object-store-data";

    /** 回收保留期：无引用 blob / 临时文件必须超过该时长才允许被清理。 */
    private long retentionSeconds = 300;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public long getRetentionSeconds() {
        return retentionSeconds;
    }

    public void setRetentionSeconds(long retentionSeconds) {
        this.retentionSeconds = retentionSeconds;
    }
}
