package com.tenx.ai.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.DramaRequest;
import com.tenx.ai.media.model.JobStatus;
import com.tenx.ai.media.model.MediaJob;
import com.tenx.ai.media.model.SpeechRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class DramaJobService {
    private final MediaJobRepository jobs;
    private final VideoJobService videos;
    private final CosyVoiceService voices;
    private final FfmpegRunner ffmpeg;
    private final MediaProperties properties;
    private final Executor executor;

    public DramaJobService(MediaJobRepository jobs, VideoJobService videos, CosyVoiceService voices,
                           FfmpegRunner ffmpeg, MediaProperties properties,
                           @Qualifier("videoJobExecutor") Executor executor) {
        this.jobs = jobs;
        this.videos = videos;
        this.voices = voices;
        this.ffmpeg = ffmpeg;
        this.properties = properties;
        this.executor = executor;
    }

    public MediaJob submit(JsonNode request) {
        ObjectNode plan = DramaRequest.validate(request);
        MediaJob job = MediaJob.video(UUID.randomUUID().toString().replace("-", ""), plan.path("model").asText());
        job.setType("drama");
        job.setDramaRequest(plan);
        job.setDramaShots(new ArrayList<MediaJob>(Collections.nCopies(plan.path("shots").size(), null)));
        double duration = 0;
        boolean audio = false;
        for (JsonNode shot : plan.path("shots")) { duration += shot.path("duration").asInt(); audio |= shot.hasNonNull("audio"); }
        job.setDynamicDurationSeconds(duration);
        job.setAudioEnabled(audio);
        schedule(job);
        return job;
    }

    /** Null index resumes missing/failed shots; an index explicitly regenerates its dependency chain. */
    public MediaJob retry(String id, Integer shotIndex) {
        return retry(id, shotIndex, null);
    }

    public MediaJob retry(String id, Integer shotIndex, JsonNode replacement) {
        MediaJob job = jobs.find(id).orElseThrow(() -> new IllegalArgumentException("Drama job not found"));
        synchronized (job) {
            if (!"drama".equals(job.getType())) throw new IllegalArgumentException("Not a drama job");
            if (job.getStatus() != JobStatus.FAILED && job.getStatus() != JobStatus.COMPLETED) {
                throw new IllegalArgumentException("Drama is already queued or running");
            }
            if (shotIndex != null) {
                JsonNode shots = job.getDramaRequest().path("shots");
                if (shotIndex < 0 || shotIndex >= shots.size()) throw new IllegalArgumentException("Invalid shot index");
                if (replacement != null) {
                    ObjectNode updated = ((ObjectNode) job.getDramaRequest()).deepCopy();
                    ((com.fasterxml.jackson.databind.node.ArrayNode) updated.path("shots")).set(shotIndex, replacement);
                    updated = DramaRequest.validate(updated);
                    job.setDramaRequest(updated);
                    double duration = 0;
                    boolean audio = false;
                    for (JsonNode shot : updated.path("shots")) { duration += shot.path("duration").asInt(); audio |= shot.hasNonNull("audio"); }
                    job.setDynamicDurationSeconds(duration);
                    job.setAudioEnabled(audio);
                }
                invalidate(job, shotIndex);
            } else if (replacement != null) {
                throw new IllegalArgumentException("shot_index is required when editing a shot");
            } else if (job.getStatus() != JobStatus.FAILED) {
                throw new IllegalArgumentException("Choose a shot to regenerate a completed drama");
            }
            schedule(job);
        }
        return job;
    }

    static void invalidate(MediaJob job, int index) {
        JsonNode shots = job.getDramaRequest().path("shots");
        job.getDramaShots().set(index, null);
        for (int next = index + 1; next < shots.size() && shots.get(next).path("continue_previous").asBoolean(); next++) {
            job.getDramaShots().set(next, null);
        }
    }

    private void schedule(MediaJob job) {
        job.setError(null);
        job.setResultUrl(null);
        job.transition(JobStatus.QUEUED);
        save(job);
        try { executor.execute(() -> run(job)); }
        catch (RuntimeException exception) { fail(job, "Drama queue is full or unavailable"); }
    }

    private void run(MediaJob parent) {
        try {
            ObjectNode plan = DramaRequest.validate(parent.getDramaRequest());
            JsonNode shots = plan.path("shots");
            // Check every voice before consuming any frame-generation work.
            for (JsonNode shot : shots) {
                SpeechRequest speech = SpeechRequest.parse(shot.get("audio"));
                if (speech != null) voices.validateVoices(speech);
            }
            List<String> ids = new ArrayList<String>();
            for (int index = 0; index < shots.size(); index++) {
                parent.setCurrentShotIndex(index);
                MediaJob child = parent.getDramaShots().get(index);
                if (child == null || child.getStatus() != JobStatus.COMPLETED) {
                    // A regenerated tail invalidates only its contiguous dependent chain.
                    invalidate(parent, index);
                    parent.transition(JobStatus.GENERATING_FRAMES);
                    save(parent);
                    ObjectNode request = plan.objectNode();
                    JsonNode shot = shots.get(index);
                    String characters = plan.path("character_prompt").asText("");
                    request.put("model", plan.path("model").asText()).put("size", plan.path("size").asText())
                            .put("duration", shot.path("duration").asInt())
                            .put("prompt", (characters.isEmpty() ? "" : characters + "\n") + shot.path("prompt").asText())
                            .put("project_id", "drama-" + parent.getId()).put("shot_id", "shot-" + (index + 1));
                    if (shot.path("continue_previous").asBoolean()) {
                        MediaJob previous = parent.getDramaShots().get(index - 1);
                        if (previous == null || previous.getStatus() != JobStatus.COMPLETED) throw new IllegalStateException("Previous shot is not ready");
                        request.put("start_video_result_id", previous.getGatewayResultId());
                    } else {
                        JsonNode image = shot.hasNonNull("start_image_result_id") ? shot.get("start_image_result_id") : plan.get("start_image_result_id");
                        if (image != null && !image.isNull()) request.set("start_image_result_id", image);
                    }
                    if (shot.hasNonNull("audio")) request.set("audio", shot.get("audio"));
                    final int position = index;
                    child = videos.generateInline(request, parent.getId(), shot.path("duration").asInt(), created -> {
                        parent.getDramaShots().set(position, created);
                        save(parent);
                    });
                    save(parent);
                    if (child.getStatus() != JobStatus.COMPLETED) {
                        throw new IllegalStateException("Shot " + (index + 1) + ": " + child.getError());
                    }
                }
                ids.add(child.getId());
            }
            parent.setCurrentShotIndex(null);
            parent.transition(JobStatus.ENCODING);
            save(parent);
            ffmpeg.concatenate(parent.getId(), ids);
            parent.setResultUrl(properties.getPublicBaseUrl().replaceAll("/+$", "")
                    + "/api/v1/videos/results/" + parent.getId() + "/content");
            parent.transition(JobStatus.COMPLETED);
            save(parent);
        } catch (Exception exception) {
            fail(parent, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private void fail(MediaJob job, String message) {
        job.setError(message.substring(0, Math.min(500, message.length())));
        job.transition(JobStatus.FAILED);
        save(job);
    }

    private void save(MediaJob job) {
        try { jobs.save(job); }
        catch (IOException exception) { throw new IllegalStateException("Cannot persist drama job", exception); }
    }
}
