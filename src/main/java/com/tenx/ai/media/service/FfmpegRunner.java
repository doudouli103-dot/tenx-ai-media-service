package com.tenx.ai.media.service;

import com.tenx.ai.media.config.MediaProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class FfmpegRunner {

    private final MediaProperties properties;

    public FfmpegRunner(MediaProperties properties) {
        this.properties = properties;
    }

    public Path encode(String jobId, Path framesDirectory, int fps) throws IOException, InterruptedException {
        Path resultsRoot = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize();
        Path videos = resultsRoot.resolve("videos");
        Path logs = resultsRoot.resolve("logs");
        Files.createDirectories(videos);
        Files.createDirectories(logs);
        Path target = videos.resolve(jobId + ".mp4");
        Path temporary = videos.resolve(jobId + ".part.mp4");
        Files.deleteIfExists(temporary);

        ProcessBuilder builder = new ProcessBuilder(
                properties.getFfmpegPath(), "-hide_banner", "-y",
                "-framerate", String.valueOf(fps),
                "-start_number", "1",
                "-i", "frame_%05d_.png",
                "-c:v", "libx264",
                "-crf", "18",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                temporary.toString());
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
