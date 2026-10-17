package com.tenx.ai.media.service;

import com.tenx.ai.media.config.MediaProperties;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Downloads authenticated Gateway frames into a persistent local job directory. */
@Component
public class FrameDownloader {

    private final MediaProperties properties;

    public FrameDownloader(MediaProperties properties) {
        this.properties = properties;
    }

    public Path download(String jobId, String resultId, String framesBaseUrl, List<String> frameFiles)
            throws IOException {
        String expectedBase = properties.getGatewayBaseUrl().replaceAll("/+$", "")
                + "/videos/results/" + resultId + "/frames";
        URI actual = parseHttpUri(framesBaseUrl);
        URI expected = parseHttpUri(expectedBase);
        if (!expected.getPath().equals(actual.getPath())) {
            throw new IllegalStateException("Gateway frame URL has an unexpected path");
        }

        Path cacheRoot = Paths.get(properties.getFrameCacheRoot()).toAbsolutePath().normalize();
        Files.createDirectories(cacheRoot);
        Path directory = cacheRoot.resolve(jobId).normalize();
        if (!directory.startsWith(cacheRoot)) {
            throw new IllegalStateException("Frame cache path escapes the configured root");
        }
        Files.createDirectory(directory);
        try {
            for (String filename : frameFiles) {
                downloadOne(expected.toString() + "/" + filename, directory.resolve(filename));
            }
            return directory;
        } catch (IOException | RuntimeException exception) {
            cleanup(directory);
            throw exception;
        }
    }

    public void cleanup(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        Path cacheRoot = Paths.get(properties.getFrameCacheRoot()).toAbsolutePath().normalize();
        Path normalized = directory.toAbsolutePath().normalize();
        if (normalized.equals(cacheRoot) || !normalized.startsWith(cacheRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Cache cleanup is best effort; a later run retries before downloading.
                }
            });
        } catch (IOException ignored) {
            // The original generation result remains more useful than a cleanup failure.
        }
    }

    private void downloadOne(String source, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1000, properties.getGatewayTimeoutMillis()));
        connection.setConnectTimeout(Math.min(timeout, 15000));
        connection.setReadTimeout(timeout);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + safe(properties.getGatewayApiKey()));
        connection.setRequestProperty("Accept", "image/png");
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Gateway frame download failed with HTTP " + status);
            }
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("image/png")) {
                throw new IOException("Gateway frame response is not image/png");
            }
            long declaredLength = connection.getContentLengthLong();
            long limit = Math.max(1, properties.getMaxFrameBytes());
            if (declaredLength > limit) {
                throw new IOException("Gateway frame exceeds the configured size limit");
            }
            Path temporary = target.resolveSibling(target.getFileName().toString() + ".part");
            Files.deleteIfExists(temporary);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) {
                        throw new IOException("Gateway frame exceeds the configured size limit");
                    }
                    output.write(buffer, 0, read);
                }
                if (total < 8) {
                    throw new IOException("Gateway returned an empty or invalid frame");
                }
            } catch (IOException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
            if (!isPng(temporary)) {
                Files.deleteIfExists(temporary);
                throw new IOException("Gateway frame does not contain PNG data");
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
    }

    private URI parseHttpUri(String value) {
        try {
            URI uri = URI.create(value).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid HTTP URL");
            }
            return uri;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Gateway returned an invalid frame URL");
        }
    }

    private boolean isPng(Path file) throws IOException {
        byte[] signature = new byte[8];
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            input.readFully(signature);
        }
        byte[] expected = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        return java.util.Arrays.equals(signature, expected);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
