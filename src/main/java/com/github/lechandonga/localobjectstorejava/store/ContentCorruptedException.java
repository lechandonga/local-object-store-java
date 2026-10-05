package com.github.lechandonga.localobjectstorejava.store;

/** 元数据引用的内容在磁盘上缺失或校验和不符（数据损坏）。 */
public class ContentCorruptedException extends StoreException {
    public ContentCorruptedException(String message) {
        super(message);
    }

    public ContentCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
