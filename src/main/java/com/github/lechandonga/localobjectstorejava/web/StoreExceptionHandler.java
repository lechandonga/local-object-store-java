package com.github.lechandonga.localobjectstorejava.web;

import com.github.lechandonga.localobjectstorejava.store.ChecksumMismatchException;
import com.github.lechandonga.localobjectstorejava.store.ContentCorruptedException;
import com.github.lechandonga.localobjectstorejava.store.ObjectDeletedException;
import com.github.lechandonga.localobjectstorejava.store.ObjectNotFoundException;
import com.github.lechandonga.localobjectstorejava.store.SnapshotNotFoundException;
import com.github.lechandonga.localobjectstorejava.store.StoreException;
import com.github.lechandonga.localobjectstorejava.store.VersionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把存储层异常映射为可区分的 HTTP 状态码与错误体。 */
@RestControllerAdvice
public class StoreExceptionHandler {

    /** 404：对象从未存在 / 版本或快照不存在。 */
    @ExceptionHandler({ObjectNotFoundException.class, VersionNotFoundException.class,
            SnapshotNotFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(StoreException e) {
        return error(HttpStatus.NOT_FOUND, errorType(e), e.getMessage());
    }

    /** 410：对象存在但当前是墓碑（已删除），与"从未存在"的 404 明确区分。 */
    @ExceptionHandler(ObjectDeletedException.class)
    public ResponseEntity<Map<String, Object>> handleDeleted(ObjectDeletedException e) {
        return error(HttpStatus.GONE, "OBJECT_DELETED", e.getMessage());
    }

    /** 422：声明校验和与实际内容不符。 */
    @ExceptionHandler(ChecksumMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleChecksum(ChecksumMismatchException e) {
        Map<String, Object> body = baseBody("CHECKSUM_MISMATCH", e.getMessage());
        body.put("expected", e.getExpected());
        body.put("actual", e.getActual());
        return ResponseEntity.unprocessableEntity().body(body);
    }

    /** 409：引用的内容缺失/损坏，或回滚到墓碑等非法状态。 */
    @ExceptionHandler(ContentCorruptedException.class)
    public ResponseEntity<Map<String, Object>> handleCorrupt(ContentCorruptedException e) {
        return error(HttpStatus.CONFLICT, "CONTENT_CORRUPTED", e.getMessage());
    }

    @ExceptionHandler(StoreException.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(StoreException e) {
        return error(HttpStatus.BAD_REQUEST, "STORE_ERROR", e.getMessage());
    }

    private static String errorType(StoreException e) {
        if (e instanceof VersionNotFoundException) {
            return "VERSION_NOT_FOUND";
        }
        if (e instanceof SnapshotNotFoundException) {
            return "SNAPSHOT_NOT_FOUND";
        }
        return "OBJECT_NOT_FOUND";
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String type, String message) {
        return ResponseEntity.status(status).body(baseBody(type, message));
    }

    private static Map<String, Object> baseBody(String type, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", type);
        body.put("message", message);
        return body;
    }
}
