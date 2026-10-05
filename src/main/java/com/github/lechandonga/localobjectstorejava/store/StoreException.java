package com.github.lechandonga.localobjectstorejava.store;

/** 存储层异常基类。 */
public class StoreException extends RuntimeException {
    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
