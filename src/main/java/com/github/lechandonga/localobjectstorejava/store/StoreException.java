package com.github.lechandonga.localobjectstorejava.store;

import org.springframework.http.HttpStatus;

/**
 * 存储层对外抛出的业务异常，errorCode 用于让调用方区分失败原因。
 */
public class StoreException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public StoreException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
