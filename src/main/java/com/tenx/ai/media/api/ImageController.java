package com.tenx.ai.media.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.service.ImageMediaService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/images")
public class ImageController {

    private static final Pattern RESULT_ID = Pattern.compile("tenx_[0-9a-f]{32}_[0-9]{5}_");
    private final ImageMediaService images;
    private final MediaProperties properties;

    public ImageController(ImageMediaService images, MediaProperties properties) {
        this.images = images;
        this.properties = properties;
    }

    @PostMapping("/generations")
    public Mono<ResponseEntity<JsonNode>> generate(@RequestBody JsonNode request) {
        return images.generate(request).map(ResponseEntity::ok);
    }

    @GetMapping("/results/{resultId}/content")
    public Mono<ResponseEntity<Resource>> download(@PathVariable String resultId) {
        if (!RESULT_ID.matcher(resultId).matches()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.<ResponseEntity<Resource>>fromCallable(() -> {
            Path root = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize();
            Path file = root.resolve("images").resolve(resultId + ".png").normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
                return ResponseEntity.<Resource>notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .contentLength(Files.size(file))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resultId + ".png\"")
                    .body((Resource) new FileSystemResource(file));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
