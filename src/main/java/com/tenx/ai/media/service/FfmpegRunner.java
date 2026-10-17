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
