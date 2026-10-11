package com.tenx.ai.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.JobStatus;
import com.tenx.ai.media.model.MediaJob;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class VideoJobService {

    private static final Pattern RESULT_ID = Pattern.compile("tenx_[0-9a-f]{32}");
    private static final Pattern FRAMES_PATH = Pattern.compile("tenx-video-frames/tenx_[0-9a-f]{32}");

    private final WebClient gateway;
    private final MediaProperties properties;
    private final MediaJobRepository jobs;
    private final Executor executor;
    private final FfmpegRunner ffmpeg;

    public VideoJobService(WebClient gatewayWebClient, MediaProperties properties,
                           MediaJobRepository jobs,
                           @Qualifier("videoJobExecutor") Executor executor,
                           FfmpegRunner ffmpeg) {
        this.gateway = gatewayWebClient;
        this.properties = properties;
        this.jobs = jobs;
        this.executor = executor;
        this.ffmpeg = ffmpeg;
    }

    public MediaJob submit(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("JSON object request body is required");
        }
        String model = requiredText(request, "model");
        requiredText(request, "prompt");
        ObjectNode requestCopy = ((ObjectNode) request).deepCopy();
        String id = UUID.randomUUID().toString().replace("-", "");
        MediaJob job = MediaJob.video(id, model);
        save(job);
        try {
            executor.execute(() -> run(job, requestCopy));
        } catch (RuntimeException exception) {
            job.setError("Video job queue is full or unavailable");
            job.transition(JobStatus.FAILED);
            save(job);
        }
        return job;
    }

    public Optional<MediaJob> find(String id) {
        return jobs.find(id);
    }

    private void run(MediaJob job, ObjectNode request) {
        try {
            job.transition(JobStatus.GENERATING_FRAMES);
            save(job);
            JsonNode manifest = gateway.post()
                    .uri("videos/generations")
                    .header(HttpHeaders.AUTHORIZATION, bearer(properties.getGatewayApiKey()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(properties.getGatewayTimeoutMillis()));
            FrameManifest frames = validateManifest(manifest);

            job.setGatewayResultId(frames.resultId);
            job.setFramesRelativePath(frames.relativePath);
            job.setFrameCount(frames.frameCount);
            job.setFps(frames.fps);
            job.transition(JobStatus.ENCODING);
            save(job);

            Path video = ffmpeg.encode(job.getId(), frames.directory, frames.fps);
            if (!Files.isRegularFile(video) || !Files.isReadable(video) || Files.size(video) == 0) {
                throw new IllegalStateException("FFmpeg completed without a readable MP4");
            }
            job.setResultUrl(properties.getPublicBaseUrl().replaceAll("/+$", "")
                    + "/api/v1/videos/results/" + job.getId() + "/content");
            job.transition(JobStatus.COMPLETED);
            save(job);
        } catch (Exception exception) {
            job.setError(safeMessage(exception));
            job.transition(JobStatus.FAILED);
            try {
                save(job);
            } catch (RuntimeException ignored) {
                // The original error remains the useful failure signal.
            }
        }
    }

    private FrameManifest validateManifest(JsonNode manifest) throws IOException {
        if (manifest == null || !"frames_ready".equals(manifest.path("status").asText())
                || !"image_sequence".equals(manifest.path("output_type").asText())) {
            throw new IllegalStateException("Gateway did not return a ready image sequence");
        }
        String resultId = manifest.path("result_id").asText("");
        String relativePath = manifest.path("frames_relative_path").asText("");
        String pattern = manifest.path("frame_pattern").asText("");
        int frameCount = manifest.path("frame_count").asInt(0);
        int fps = manifest.path("fps").asInt(0);
        if (!RESULT_ID.matcher(resultId).matches()
                || !FRAMES_PATH.matcher(relativePath).matches()
                || !relativePath.endsWith(resultId)
                || !"frame_%05d_.png".equals(pattern)
                || frameCount < 1 || frameCount > 1000
                || fps < 1 || fps > 120) {
            throw new IllegalStateException("Gateway returned an invalid frame manifest");
        }

        Path root = Paths.get(properties.getFramesRoot()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IllegalStateException("SMB frame root is unavailable: " + root);
        }
        Path directory = root.resolve(relativePath).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory) || !Files.isReadable(directory)) {
            throw new IllegalStateException("Gateway frame directory is unavailable");
        }
        Path realRoot = root.toRealPath();
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(realRoot)) {
            throw new IllegalStateException("Gateway frame directory escapes the configured SMB root");
        }

        JsonNode names = manifest.path("frame_files");
        if (!names.isArray() || names.size() != frameCount) {
            throw new IllegalStateException("Gateway frame list does not match frame_count");
        }
        for (int index = 1; index <= frameCount; index++) {
            String expected = String.format(java.util.Locale.ROOT, "frame_%05d_.png", index);
            if (!expected.equals(names.get(index - 1).asText())) {
                throw new IllegalStateException("Gateway frame list is not contiguous");
            }
            Path file = realDirectory.resolve(expected).normalize();
            if (!file.startsWith(realDirectory)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(file)) {
                throw new IllegalStateException("Video frame is missing or unreadable: " + expected);
            }
        }
        return new FrameManifest(resultId, relativePath, realDirectory, frameCount, fps);
    }

    private void save(MediaJob job) {
        try {
            jobs.save(job);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist media job", exception);
        }
    }

    private String requiredText(JsonNode request, String field) {
        String value = request.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String bearer(String apiKey) {
        return "Bearer " + (apiKey == null ? "" : apiKey);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static class FrameManifest {
        private final String resultId;
        private final String relativePath;
        private final Path directory;
        private final int frameCount;
        private final int fps;

        private FrameManifest(String resultId, String relativePath, Path directory, int frameCount, int fps) {
            this.resultId = resultId;
            this.relativePath = relativePath;
            this.directory = directory;
            this.frameCount = frameCount;
            this.fps = fps;
        }
    }
}
