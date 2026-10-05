package com.github.lechandonga.localobjectstorejava.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.lechandonga.localobjectstorejava.store.ObjectStore;

@RestController
public class GcController {

    private final ObjectStore store;

    public GcController(ObjectStore store) {
        this.store = store;
    }

    @PostMapping("/gc")
    public Map<String, Object> gc() {
        ObjectStore.GcResult r = store.garbageCollect();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionsPruned", r.versionsPruned);
        body.put("blobsDeleted", r.blobsDeleted);
        body.put("metadataDeleted", r.metadataDeleted);
        body.put("bytesReclaimed", r.bytesReclaimed);
        return body;
    }
}
