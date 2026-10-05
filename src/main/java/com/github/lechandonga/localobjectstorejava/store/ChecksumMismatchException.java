package com.github.lechandonga.localobjectstorejava.store;

/** 上传声明的校验和与实际内容计算结果不一致。 */
public class ChecksumMismatchException extends StoreException {
    private final String expected;
    private final String actual;

    public ChecksumMismatchException(String expected, String actual) {
        super("checksum mismatch: expected=" + expected + ", actual=" + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public String getExpected() {
        return expected;
    }

    public String getActual() {
        return actual;
    }
}
