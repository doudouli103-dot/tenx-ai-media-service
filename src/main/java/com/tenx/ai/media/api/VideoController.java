package com.tenx.ai.media.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.JobStatus;
import com.tenx.ai.media.model.MediaJob;
import com.tenx.ai.media.service.MediaJobRepository;
import com.tenx.ai.media.service.VideoJobService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/videos")
public class VideoController {

    private static final Pattern JOB_ID = Pattern.compile("[0-9a-f]{32}");
    private final VideoJobService videoJobs;
    private final MediaJobRepository jobs;
    private final MediaProperties properties;

    public VideoController(VideoJobService videoJobs, MediaJobRepository jobs, MediaProperties properties) {
        this.videoJobs = videoJobs;
        this.jobs = jobs;
        this.properties = properties;
    }

    @PostMapping("/generations")
    public ResponseEntity<MediaJob> generate(@RequestBody JsonNode request) {
        return ResponseEntity.accepted().body(videoJobs.submit(request));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<MediaJob> status(@PathVariable String jobId) {
        if (!JOB_ID.matcher(jobId).matches()) {
            return ResponseEntity.badRequest().build();
        }
        Optional<MediaJob> job = videoJobs.find(jobId);
        return job.isPresent() ? ResponseEntity.ok(job.get()) : ResponseEntity.notFound().build();
    }

    @GetMapping("/jobs")
    public List<MediaJob> jobs() {
        return jobs.list();
    }

    @GetMapping("/results/{jobId}/content")
    public Mono<ResponseEntity<Resource>> download(@PathVariable String jobId) {
        if (!JOB_ID.matcher(jobId).matches()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        Optional<MediaJob> found = jobs.find(jobId);
        if (!found.isPresent() || found.get().getStatus() != JobStatus.COMPLETED) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.<ResponseEntity<Resource>>fromCallable(() -> {
            Path root = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize();
            Path file = root.resolve("videos").resolve(jobId + ".mp4").normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
                return ResponseEntity.<Resource>notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .contentLength(Files.size(file))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + jobId + ".mp4\"")
                    .body((Resource) new FileSystemResource(file));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
