package com.tenx.ai.media.model;

import java.nio.file.Path;

public class SpeechAudio {
    private final Path path;
    private final String id;
    private final double durationSeconds;

    public SpeechAudio(Path path, String id, double durationSeconds) {
        this.path = path;
        this.id = id;
        this.durationSeconds = durationSeconds;
    }

    public Path getPath() { return path; }
    public String getId() { return id; }
    public double getDurationSeconds() { return durationSeconds; }
}
