package com.github.lechandonga.localobjectstorejava.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.github.lechandonga.localobjectstorejava.store.StoreException;

@RestControllerAdvice
public class StoreExceptionHandler {

    @ExceptionHandler(StoreException.class)
    public ResponseEntity<Map<String, Object>> handle(StoreException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Map.of("error", e.getErrorCode(), "message", e.getMessage()));
    }
}
