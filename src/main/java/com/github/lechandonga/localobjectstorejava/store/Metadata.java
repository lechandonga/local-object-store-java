package com.github.lechandonga.localobjectstorejava.store;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个对象键的元数据，整体序列化为一个 JSON 文件并通过原子替换写入。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Metadata {

    /** 对象键。 */
    public String key;

    /** 当前版本号（可能指向墓碑版本）；无任何版本时为 null。 */
    public Long current;

    public List<Version> versions = new ArrayList<>();

    public List<Snapshot> snapshots = new ArrayList<>();

    public static class Version {
        public long version;
        /** 内容 blob 的 sha256；墓碑版本为 null。 */
        public String checksum;
        public long size;
        public long createdAt;
        /** true 表示这是一次删除产生的墓碑版本。 */
        public boolean deleted;

        public Version() {
        }

        public static Version live(long version, String checksum, long size, long createdAt) {
            Version v = new Version();
            v.version = version;
            v.checksum = checksum;
            v.size = size;
            v.createdAt = createdAt;
            return v;
        }

        public static Version tombstone(long version, long createdAt) {
            Version v = new Version();
            v.version = version;
            v.createdAt = createdAt;
            v.deleted = true;
            return v;
        }
    }

    public static class Snapshot {
        public String id;
        public long version;
        public String checksum;
        public long size;
        public long createdAt;
    }
}
