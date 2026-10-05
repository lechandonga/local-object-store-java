package com.github.lechandonga.localobjectstorejava.store;

/** 对象从未存在过（key 没有任何版本记录）。 */
public class ObjectNotFoundException extends StoreException {
    public ObjectNotFoundException(String key) {
        super("object not found: " + key);
    }
}
