package com.github.lechandonga.localobjectstorejava.store;

import java.util.ArrayList;
import java.util.List;

/** 一个对象 key 的完整版本链元数据。 */
public class KeyMetadata {

    private String key;
    private long nextVersion = 1;
    /** 当前指针：0 表示无当前版本（尚未有过任何写入）。 */
    private long currentVersion;
    private List<VersionInfo> versions = new ArrayList<>();

    public KeyMetadata() {
    }

    public KeyMetadata(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public long getNextVersion() {
        return nextVersion;
    }

    public void setNextVersion(long nextVersion) {
        this.nextVersion = nextVersion;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(long currentVersion) {
        this.currentVersion = currentVersion;
    }

    public List<VersionInfo> getVersions() {
        return versions;
    }

    public void setVersions(List<VersionInfo> versions) {
        this.versions = versions;
    }
}
