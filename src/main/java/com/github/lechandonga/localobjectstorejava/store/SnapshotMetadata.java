package com.github.lechandonga.localobjectstorejava.store;

import java.util.HashMap;
import java.util.Map;

/** 快照的 JSON 持久化形态（Jackson 序列化 record 需要可变 POJO 兼容性，故单独定义）。 */
public class SnapshotMetadata {

    private String id;
    private long createdAt;
    private Map<String, Ref> refs = new HashMap<>();

    public SnapshotMetadata() {
    }

    public SnapshotMetadata(String id, long createdAt, Map<String, Ref> refs) {
        this.id = id;
        this.createdAt = createdAt;
        this.refs = refs;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, Ref> getRefs() {
        return refs;
    }

    public void setRefs(Map<String, Ref> refs) {
        this.refs = refs;
    }

    public static class Ref {
        private long version;
        private String checksum;

        public Ref() {
        }

        public Ref(long version, String checksum) {
            this.version = version;
            this.checksum = checksum;
        }

        public long getVersion() {
            return version;
        }

        public void setVersion(long version) {
            this.version = version;
        }

        public String getChecksum() {
            return checksum;
        }

        public void setChecksum(String checksum) {
            this.checksum = checksum;
        }
    }
}
