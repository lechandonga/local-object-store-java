package com.github.lechandonga.localobjectstorejava.store;

/** 对象存在版本记录，但当前版本是墓碑（已删除）。 */
public class ObjectDeletedException extends StoreException {
    private final long deletedVersion;

    public ObjectDeletedException(String key, long deletedVersion) {
        super("object deleted: " + key + " (tombstone version " + deletedVersion + ")");
        this.deletedVersion = deletedVersion;
    }

    public long getDeletedVersion() {
        return deletedVersion;
    }
}
