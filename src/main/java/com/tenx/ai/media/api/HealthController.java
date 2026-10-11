package com.tenx.ai.media.api;

import com.tenx.ai.media.config.MediaProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final MediaProperties properties;

    public HealthController(MediaProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        Path frameCache = Paths.get(properties.getFrameCacheRoot()).toAbsolutePath().normalize();
        Path results = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("status", "ok");
        response.put("frame_transport", "authenticated_http");
        response.put("frame_cache_available", writableDirectory(frameCache));
        response.put("results_root_available", Files.isDirectory(results) && Files.isWritable(results));
        return response;
    }

    private boolean writableDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return Files.isDirectory(path) && Files.isWritable(path);
        } catch (Exception ignored) {
            return false;
        }
    }
}
