package com.github.lechandonga.localobjectstorejava.store;

import java.util.Map;

/**
 * 快照元数据。
 *
 * @param id        快照 ID
 * @param createdAt 创建时间（epoch millis）
 * @param refs      key -> (版本号, 校验和)；创建时已删除/不存在的 key 不在 map 中
 */
public record SnapshotInfo(String id, long createdAt, Map<String, SnapshotRef> refs) {

    public record SnapshotRef(long version, String checksum) {
    }
}
