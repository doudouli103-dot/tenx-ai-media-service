package com.tenx.ai.media.service;

import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.SpeechAudio;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class FfmpegRunner {

    private final MediaProperties properties;

    public FfmpegRunner(MediaProperties properties) {
        this.properties = properties;
    }

    public Path encode(String jobId, Path framesDirectory, int fps) throws IOException, InterruptedException {
        return encode(jobId, framesDirectory, fps, 0, null);
    }

    /** Exact-duration shots: silence may pad audio, but video is never frozen to pad speech. */
    public Path encodeShot(String jobId, Path directory, int fps, int frameCount,
                           SpeechAudio audio, int duration) throws IOException, InterruptedException {
        if (fps != 24 || duration < 1 || duration > 5 || frameCount < fps * duration
                || (audio != null && audio.getDurationSeconds() > duration)) {
            throw new IllegalArgumentException("Shot frames/audio do not fit the requested duration");
        }
        List<String> command = new ArrayList<String>(Arrays.asList(properties.getFfmpegPath(), "-hide_banner", "-y",
                "-framerate", String.valueOf(fps), "-start_number", "1", "-i", "frame_%05d_.png"));
        if (audio == null) command.addAll(Arrays.asList("-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono"));
        else command.addAll(Arrays.asList("-i", audio.getPath().toAbsolutePath().toString()));
        command.addAll(Arrays.asList("-map", "0:v:0", "-map", "1:a:0", "-af", "apad",
                "-t", String.valueOf(duration), "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ar", "24000", "-ac", "1", "-b:a", "128k"));
        return executeMedia(jobId, directory, command);
    }

    public Path concatenate(String jobId, List<String> shotIds) throws IOException, InterruptedException {
        if (shotIds.size() < 2 || shotIds.size() > 12) throw new IllegalArgumentException("Invalid shot count");
        Path videos = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize().resolve("videos");
        Files.createDirectories(videos);
        StringBuilder list = new StringBuilder();
        for (String id : shotIds) {
            if (!id.matches("[0-9a-f]{32}") || !Files.isRegularFile(videos.resolve(id + ".mp4"))) {
                throw new IllegalArgumentException("Completed shot video is unavailable");
            }
            list.append("file '").append(id).append(".mp4'\n");
        }
        if (!jobId.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid job ID");
        Path manifest = videos.resolve(jobId + ".concat");
        Files.write(manifest, list.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            return executeMedia(jobId, videos, new ArrayList<String>(Arrays.asList(properties.getFfmpegPath(),
                    "-hide_banner", "-y", "-f", "concat", "-safe", "1", "-i", manifest.getFileName().toString(),
                    "-map", "0:v:0", "-map", "0:a:0", "-c", "copy")));
        } finally {
            Files.deleteIfExists(manifest);
        }
    }

    private Path executeMedia(String jobId, Path directory, List<String> command) throws IOException, InterruptedException {
        if (!jobId.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid job ID");
        Path root = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize();
        Path videos = root.resolve("videos");
        Path logs = root.resolve("logs");
        Files.createDirectories(videos);
        Files.createDirectories(logs);
        Path temporary = videos.resolve(jobId + ".part.mp4");
        Path target = videos.resolve(jobId + ".mp4");
        command.addAll(Arrays.asList("-movflags", "+faststart", temporary.toString()));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(logs.resolve(jobId + ".log").toFile()).start();
        try {
            if (!process.waitFor(properties.getFfmpegTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("FFmpeg timed out");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
                throw new IllegalStateException("FFmpeg failed; inspect the job encoding log");
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            if (!process.isAlive()) Files.deleteIfExists(temporary);
        }
    }

    public Path encode(String jobId, Path framesDirectory, int fps, int frameCount, SpeechAudio audio)
            throws IOException, InterruptedException {
        if (!jobId.matches("[0-9a-f]{32}") || fps < 1) throw new IllegalArgumentException("Invalid encoding parameters");
        Path resultsRoot = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize();
        Path videos = resultsRoot.resolve("videos");
        Path logs = resultsRoot.resolve("logs");
        Files.createDirectories(videos);
        Files.createDirectories(logs);
        Path target = videos.resolve(jobId + ".mp4");
        Path temporary = videos.resolve(jobId + ".part.mp4");
        Files.deleteIfExists(temporary);

        List<String> command = new ArrayList<String>(Arrays.asList(
                properties.getFfmpegPath(), "-hide_banner", "-y",
                "-framerate", String.valueOf(fps),
                "-start_number", "1",
                "-i", "frame_%05d_.png"));
        if (audio != null) {
            if (frameCount < 1 || !Double.isFinite(audio.getDurationSeconds()) || audio.getDurationSeconds() <= 0
                    || !Files.isRegularFile(audio.getPath())) throw new IllegalArgumentException("Invalid speech audio");
            double duration = Math.max(frameCount / (double) fps, audio.getDurationSeconds());
            command.addAll(Arrays.asList("-i", audio.getPath().toAbsolutePath().toString(),
                    "-map", "0:v:0", "-map", "1:a:0",
                    "-vf", "tpad=stop_mode=clone:stop=-1", "-af", "apad",
                    "-t", String.format(Locale.ROOT, "%.6f", duration),
                    "-c:a", "aac", "-b:a", "128k"));
        }
        command.addAll(Arrays.asList(
                "-c:v", "libx264",
                "-crf", "18",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                temporary.toString()));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(framesDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logs.resolve(jobId + ".ffmpeg.log").toFile());
        Process process = builder.start();
        boolean completed = process.waitFor(properties.getFfmpegTimeoutMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("FFmpeg encoding timed out");
        }
        if (process.exitValue() != 0) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("FFmpeg exited with code " + process.exitValue());
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
