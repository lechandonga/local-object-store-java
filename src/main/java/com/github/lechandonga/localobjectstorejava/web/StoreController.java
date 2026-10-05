package com.github.lechandonga.localobjectstorejava.web;

import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.lechandonga.localobjectstorejava.store.Metadata;
import com.github.lechandonga.localobjectstorejava.store.ObjectStore;
import com.github.lechandonga.localobjectstorejava.store.StoreException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 对象存储 HTTP 接口（key 允许包含 /，如 reports/2026/summary）。
 * 由于 key 自身可能含 "/"，控制器只映射 /objects/** 并在内部按后缀分发：
 * - POST   /objects/{key}                          上传（Header X-Checksum-SHA256 必填）
 * - GET    /objects/{key}                          下载当前版本
 * - DELETE /objects/{key}                          墓碑删除
 * - GET    /objects/{key}/versions                 版本列表
 * - GET    /objects/{key}/versions/{n}             下载指定版本
 * - POST   /objects/{key}/versions/rollback        请求体 {"version": n}
 * - POST   /objects/{key}/snapshots                创建快照
 * - GET    /objects/{key}/snapshots                快照列表
 * - GET    /objects/{key}/snapshots/{id}           下载快照内容
 * - DELETE /objects/{key}/snapshots/{id}           释放快照
 */
@RestController
@RequestMapping("/objects/**")
public class StoreController {

    private static final String PREFIX = "/objects/";

    private final ObjectStore store;

    public StoreController(ObjectStore store) {
        this.store = store;
    }

    @PostMapping
    public Object post(HttpServletRequest request,
            @RequestHeader(value = "X-Checksum-SHA256", required = false) String checksum) {
        String path = extractPath(request);
        if (path.endsWith("/versions/rollback")) {
            String key = validateKey(stripSuffix(path, "/versions/rollback"));
            Map<String, Long> parsed = parseJson(readBody(request));
            Long version = parsed.get("version");
            long newVersion = store.rollback(key, version);
            return Map.of("key", key, "rolledBackFrom", version, "version", newVersion);
        }
        if (path.endsWith("/snapshots")) {
            return store.createSnapshot(validateKey(stripSuffix(path, "/snapshots")));
        }
        String key = validateKey(path);
        ObjectStore.UploadResult r = store.put(key, checksum, wrap(request));
        return Map.of(
                "key", key,
                "version", r.version,
                "checksum", r.checksum,
                "size", r.size,
                "deduplicated", r.deduplicated);
    }

    @GetMapping
    public Object get(HttpServletRequest request) {
        String path = extractPath(request);
        int snapshotsIdx = path.indexOf("/snapshots");
        if (snapshotsIdx >= 0) {
            String rest = path.substring(snapshotsIdx + "/snapshots".length());
            String key = validateKey(path.substring(0, snapshotsIdx));
            if (rest.isEmpty()) {
                return store.listSnapshots(key);
            }
            String id = rest.substring(1);
            return objectResponse(store.getSnapshot(key, id));
        }
        int versionsIdx = path.indexOf("/versions");
        if (versionsIdx >= 0) {
            String rest = path.substring(versionsIdx + "/versions".length());
            String key = validateKey(path.substring(0, versionsIdx));
            if (rest.isEmpty()) {
                return store.listVersions(key);
            }
            String versionText = rest.substring(1);
            long version = parseVersion(versionText);
            return objectResponse(store.getVersion(key, version));
        }
        return objectResponse(store.get(validateKey(path)));
    }

    @DeleteMapping
    public Map<String, Object> delete(HttpServletRequest request) {
        String path = extractPath(request);
        int snapshotsIdx = path.indexOf("/snapshots/");
        if (snapshotsIdx >= 0) {
            String key = validateKey(path.substring(0, snapshotsIdx));
            String id = path.substring(snapshotsIdx + "/snapshots/".length());
            if (id.isEmpty()) {
                throw new StoreException(HttpStatus.BAD_REQUEST, "SNAPSHOT_ID_REQUIRED",
                        "快照 id 不能为空");
            }
            store.releaseSnapshot(key, id);
            return Map.of("key", key, "snapshotId", id, "released", true);
        }
        String key = validateKey(path);
        long version = store.delete(key);
        return Map.of("key", key, "tombstoneVersion", version, "deleted", true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> parseJson(byte[] body) {
        if (body == null || body.length == 0) {
            throw new StoreException(HttpStatus.BAD_REQUEST, "BODY_REQUIRED", "请求体不能为空");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (node.path("version").isMissingNode()) {
                throw new StoreException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED",
                        "rollback 请求必须包含 version");
            }
            return Map.of("version", node.get("version").asLong());
        } catch (Exception e) {
            throw new StoreException(HttpStatus.BAD_REQUEST, "INVALID_JSON",
                    "请求体必须是 JSON: " + e.getMessage());
        }
    }

    private static byte[] readBody(HttpServletRequest request) {
        try {
            return request.getInputStream().readAllBytes();
        } catch (java.io.IOException e) {
            throw new StoreException(HttpStatus.BAD_REQUEST, "READ_BODY_FAILED",
                    "读取请求体失败: " + e.getMessage());
        }
    }

    private static java.io.InputStream wrap(HttpServletRequest request) {
        try {
            return request.getInputStream();
        } catch (java.io.IOException e) {
            throw new StoreException(HttpStatus.BAD_REQUEST, "READ_BODY_FAILED",
                    "读取请求体失败: " + e.getMessage());
        }
    }

    private static long parseVersion(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new StoreException(HttpStatus.BAD_REQUEST, "INVALID_VERSION",
                    "版本号必须是数字: " + text);
        }
    }

    private static String extractPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith(PREFIX) ? uri.substring(PREFIX.length()) : uri;
    }

    private static String stripSuffix(String path, String suffix) {
        return path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : path;
    }

    private static String validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new StoreException(HttpStatus.BAD_REQUEST, "KEY_REQUIRED", "对象键不能为空");
        }
        return key;
    }

    private static ResponseEntity<Resource> objectResponse(ObjectStore.ReadResult r) {
        Resource resource = new FileSystemResource(r.path);
        return ResponseEntity.ok()
                .header("X-Object-Version", Long.toString(r.version))
                .header("X-Checksum-SHA256", r.checksum)
                .contentLength(r.size)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
                .body(resource);
    }
}
