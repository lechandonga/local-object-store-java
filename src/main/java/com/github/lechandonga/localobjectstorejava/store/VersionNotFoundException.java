package com.github.lechandonga.localobjectstorejava.store;

/** 指定的历史版本不存在。 */
public class VersionNotFoundException extends StoreException {
    public VersionNotFoundException(String key, long version) {
        super("version not found: " + key + "#v" + version);
    }
}
