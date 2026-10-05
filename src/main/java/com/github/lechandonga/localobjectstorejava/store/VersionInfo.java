package com.github.lechandonga.localobjectstorejava.store;

/**
 * 单个版本记录。
 *
 * @param version   单调递增版本号，从 1 开始
 * @param checksum  内容 blob 的 SHA-256；墓碑版本为 null
 * @param size      内容字节数；墓碑版本为 0
 * @param timestamp 创建时间（epoch millis）
 * @param tombstone 是否为墓碑版本（删除标记）
 * @param reason    版本产生原因：PUT / DELETE / ROLLBACK
 */
public record VersionInfo(long version, String checksum, long size, long timestamp,
                          boolean tombstone, String reason) {

    public static VersionInfo content(long version, String checksum, long size, long timestamp, String reason) {
        return new VersionInfo(version, checksum, size, timestamp, false, reason);
    }

    public static VersionInfo tombstone(long version, long timestamp) {
        return new VersionInfo(version, null, 0L, timestamp, true, "DELETE");
    }
}
