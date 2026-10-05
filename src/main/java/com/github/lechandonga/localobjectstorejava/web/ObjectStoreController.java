package com.github.lechandonga.localobjectstorejava.web;

import com.github.lechandonga.localobjectstorejava.store.ObjectStoreService;
import com.github.lechandonga.localobjectstorejava.store.SnapshotInfo;
import com.github.lechandonga.localobjectstorejava.store.VersionInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 接口：
 * <pre>
 * PUT    /objects/{key}                 上传（Header: X-Content-Checksum 声明 SHA-256，可选）
 * GET    /objects/{key}                 下载当前版本
 * GET    /objects/{key}/versions        列出全部版本
 * GET    /objects/{key}/versions/{v}    下载指定历史版本
 * POST   /objects/{key}/versions/{v}/rollback  回滚当前指针到指定版本
 * DELETE /objects/{key}                 墓碑删除
 * GET    /objects                       列出对象
 * POST   /snapshots                     创建快照
 * GET    /snapshots                     列出快照
 * GET    /snapshots/{id}                查看快照详情
 * GET    /snapshots/{id}/objects/{key}  读取快照固定的版本
 * DELETE /snapshots/{id}                释放快照
 * POST   /gc?retentionSeconds=300       触发回收
 * </pre>
 */
@RestController
@RequestMapping
public class ObjectStoreController {

    private final ObjectStoreService service;

    public ObjectStoreController(ObjectStoreService service) {
        this.service = service;
    }

    @PutMapping("/objects/{key}")
    public Map<String, Object> put(@PathVariable String key,
                                   jakarta.servlet.http.HttpServletRequest request,
                                   @RequestHeader(value = "X-Content-Checksum", required = false)
                                   String checksum) {
        try {
            byte[] payload = org.springframework.util.StreamUtils.copyToByteArray(request.getInputStream());
            VersionInfo version = service.put(key, new java.io.ByteArrayInputStream(payload), checksum);
            return versionBody(key, version);
        } catch (java.io.IOException e) {
            throw new com.github.lechandonga.localobjectstorejava.store.StoreException(
                    "failed reading upload body for " + key, e);
        }
    }

    @GetMapping("/objects/{key}")
    public ResponseEntity<Resource> get(@PathVariable String key) {
        ObjectStoreService.ContentRead read = service.read(key);
        return contentResponse(read);
    }

    @GetMapping("/objects/{key}/versions")
    public Map<String, Object> listVersions(@PathVariable String key) {
        List<VersionInfo> versions = service.listVersions(key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("versions", versions.stream().map(v -> versionBody(key, v)).toList());
        return body;
    }

    @GetMapping("/objects/{key}/versions/{version}")
    public ResponseEntity<Resource> getVersion(@PathVariable String key,
                                                  @PathVariable long version) {
        return contentResponse(service.readVersion(key, version));
    }

    @PostMapping("/objects/{key}/versions/{version}/rollback")
    public Map<String, Object> rollback(@PathVariable String key, @PathVariable long version) {
        return versionBody(key, service.rollback(key, version));
    }

    @DeleteMapping("/objects/{key}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String key,
            @RequestParam(value = "purgeHistory", required = false, defaultValue = "false")
            boolean purgeHistory) {
        if (purgeHistory) {
            service.purgeHistory(key);
            return ResponseEntity.ok(Map.of("key", key, "purged", true));
        }
        return ResponseEntity.ok(versionBody(key, service.delete(key)));
    }

    @GetMapping("/objects")
    public Map<String, Object> listObjects() {
        return Map.of("objects", service.listObjects());
    }

    // ---- snapshots ----

    @PostMapping("/snapshots")
    public Map<String, Object> createSnapshot() {
        return snapshotBody(service.createSnapshot());
    }

    @GetMapping("/snapshots")
    public Map<String, Object> listSnapshots() {
        return Map.of("snapshots", service.listSnapshots().stream()
                .map(ObjectStoreController::snapshotSummary).toList());
    }

    @GetMapping("/snapshots/{id}")
    public Map<String, Object> getSnapshot(@PathVariable String id) {
        return snapshotBody(service.getSnapshot(id));
    }

    @GetMapping("/snapshots/{id}/objects/{key}")
    public ResponseEntity<Resource> readSnapshot(@PathVariable String id, @PathVariable String key) {
        return contentResponse(service.readSnapshot(id, key));
    }

    @DeleteMapping("/snapshots/{id}")
    public Map<String, Object> releaseSnapshot(@PathVariable String id) {
        service.releaseSnapshot(id);
        return Map.of("released", id);
    }

    // ---- gc ----

    @PostMapping("/gc")
    public Map<String, Object> gc(@RequestParam(value = "retentionSeconds", required = false)
                                  Long retentionSeconds) {
        Duration retention = retentionSeconds == null
                ? null : Duration.ofSeconds(retentionSeconds);
        ObjectStoreService.GcReport report = retention == null
                ? service.garbageCollect() : service.garbageCollect(retention);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referencedBlobs", report.referencedBlobs());
        body.put("blobsBefore", report.blobsBefore());
        body.put("blobsAfter", report.blobsAfter());
        body.put("removedCount", report.removedCount());
        body.put("removedChecksums", report.removedChecksums());
        return body;
    }

    // ---- helpers ----

    private static ResponseEntity<Resource> contentResponse(ObjectStoreService.ContentRead read) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("X-Content-Checksum", read.checksum());
        headers.setContentLength(read.size());
        return new ResponseEntity<>(new InputStreamResource(read.stream()), headers, 200);
    }

    private static Map<String, Object> versionBody(String key, VersionInfo v) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("version", v.version());
        body.put("tombstone", v.tombstone());
        body.put("reason", v.reason());
        body.put("timestamp", v.timestamp());
        if (!v.tombstone()) {
            body.put("checksum", v.checksum());
            body.put("size", v.size());
        }
        return body;
    }

    private static Map<String, Object> snapshotBody(SnapshotInfo snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", snapshot.id());
        body.put("createdAt", snapshot.createdAt());
        Map<String, Object> refs = new LinkedHashMap<>();
        snapshot.refs().forEach((k, v) -> {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("version", v.version());
            ref.put("checksum", v.checksum());
            refs.put(k, ref);
        });
        body.put("refs", refs);
        return body;
    }

    private static Map<String, Object> snapshotSummary(SnapshotInfo snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", snapshot.id());
        body.put("createdAt", snapshot.createdAt());
        body.put("objectCount", snapshot.refs().size());
        return body;
    }
}
