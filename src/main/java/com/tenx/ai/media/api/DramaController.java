package com.tenx.ai.media.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tenx.ai.media.model.MediaJob;
import com.tenx.ai.media.service.DramaJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dramas")
public class DramaController {
    private final DramaJobService dramas;
    public DramaController(DramaJobService dramas) { this.dramas = dramas; }

    @PostMapping("/generations")
    public ResponseEntity<MediaJob> generate(@RequestBody JsonNode request) {
        return ResponseEntity.accepted().body(dramas.submit(request));
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<MediaJob> retry(@PathVariable String id, @RequestBody JsonNode body) {
        if (!id.matches("[0-9a-f]{32}") || body == null || !body.isObject()
                || (body.has("shot_index") && !body.path("shot_index").isInt())) {
            throw new IllegalArgumentException("Invalid retry request");
        }
        Integer index = body.has("shot_index") ? body.path("shot_index").intValue() : null;
        return ResponseEntity.accepted().body(body.has("shot") ? dramas.retry(id, index, body.get("shot")) : dramas.retry(id, index));
    }
}
