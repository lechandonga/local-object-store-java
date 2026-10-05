package com.github.lechandonga.localobjectstorejava.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "objectstore")
public class StoreProperties {

    /** 本地存储根目录，blob 与元数据均落在此目录下。 */
    private Path root = Path.of("data", "object-store");

    /** 回收保留期：只有无引用且最后修改时间早于 now-retention 的内容才允许被回收。 */
    private Duration retention = Duration.ofHours(24);

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }
}
