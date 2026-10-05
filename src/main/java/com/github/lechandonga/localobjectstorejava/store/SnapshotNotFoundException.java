package com.github.lechandonga.localobjectstorejava.store;

/** 快照不存在或已释放。 */
public class SnapshotNotFoundException extends StoreException {
    public SnapshotNotFoundException(String snapshotId) {
        super("snapshot not found: " + snapshotId);
    }
}
