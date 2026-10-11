package com.tenx.ai.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.JobStatus;
import com.tenx.ai.media.model.MediaJob;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

@Repository
public class MediaJobRepository {

    private final ConcurrentHashMap<String, MediaJob> jobs = new ConcurrentHashMap<String, MediaJob>();
    private final ObjectMapper mapper;
    private final Path jobsDirectory;

    public MediaJobRepository(ObjectMapper mapper, MediaProperties properties) {
        this.mapper = mapper;
        this.jobsDirectory = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize().resolve("jobs");
    }

    @PostConstruct
    public void load() throws IOException {
        Files.createDirectories(jobsDirectory);
        try (Stream<Path> files = Files.list(jobsDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    MediaJob job = mapper.readValue(path.toFile(), MediaJob.class);
                    if (job.getStatus() == JobStatus.QUEUED
                            || job.getStatus() == JobStatus.GENERATING_FRAMES
                            || job.getStatus() == JobStatus.ENCODING) {
                        job.setError("Media service restarted before the job completed");
                        job.transition(JobStatus.FAILED);
                    }
                    jobs.put(job.getId(), job);
                    save(job);
                } catch (IOException ignored) {
                    // Keep startup available when one old metadata file is unreadable.
                }
            });
        }
    }

    public synchronized MediaJob save(MediaJob job) throws IOException {
        jobs.put(job.getId(), job);
        Path target = jobsDirectory.resolve(job.getId() + ".json");
        Path temporary = jobsDirectory.resolve(job.getId() + ".json.tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), job);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return job;
    }

    public Optional<MediaJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<MediaJob> list() {
        List<MediaJob> result = new ArrayList<MediaJob>(jobs.values());
        result.sort(Comparator.comparingLong(MediaJob::getCreatedAt).reversed());
        return result;
    }
}
