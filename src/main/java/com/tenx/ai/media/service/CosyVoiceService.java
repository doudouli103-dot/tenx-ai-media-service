package com.tenx.ai.media.service;

import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.SpeechAudio;
import com.tenx.ai.media.model.SpeechRequest;
import io.netty.channel.ChannelOption;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

@Service
public class CosyVoiceService {
    private final MediaProperties properties;
    private final WebClient client;

    public CosyVoiceService(MediaProperties properties) {
        this.properties = properties;
        this.client = WebClient.builder()
                .baseUrl(properties.getCosyvoiceBaseUrl().replaceAll("/+$", "") + "/")
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .responseTimeout(Duration.ofMillis(properties.getCosyvoiceTimeoutMillis()))))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    public SpeechAudio generate(String jobId, SpeechRequest speech) throws IOException {
        if (!jobId.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid job ID");
        ResponseEntity<byte[]> response;
        try {
            response = client.post().uri("v1/audio/speech")
                    .headers(headers -> {
                        String key = properties.getCosyvoiceApiKey();
                        if (key != null && !key.trim().isEmpty()) headers.setBearerAuth(key);
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(speech.getPayload()).retrieve().toEntity(byte[].class)
                    .block(Duration.ofMillis(properties.getCosyvoiceTimeoutMillis()));
        } catch (WebClientResponseException exception) {
            throw new IllegalStateException("CosyVoice speech generation failed (HTTP "
                    + exception.getRawStatusCode() + "); check voice, credentials and service availability");
        } catch (WebClientRequestException exception) {
            throw new IllegalStateException("Cannot connect to CosyVoice; check the service and Docker network");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("CosyVoice request timed out or could not be completed");
        }
        byte[] wav = response == null ? null : response.getBody();
        double duration = wavDuration(wav);
        Path root = Paths.get(properties.getAudioRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(jobId + ".wav");
        Path temporary = root.resolve(jobId + ".part.wav");
        try {
            Files.write(temporary, wav);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        String id = response.getHeaders().getFirst("X-Audio-Id");
        return new SpeechAudio(target, id != null && id.matches("[0-9a-f]{32}") ? id : null, duration);
    }

    private double wavDuration(byte[] wav) throws IOException {
        if (wav == null || wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F'
                || wav[3] != 'F' || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
            throw new IllegalStateException("CosyVoice did not return a valid WAV");
        }
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            double duration = stream.getFrameLength() / (double) stream.getFormat().getFrameRate();
            long dataBytes = stream.getFrameLength() * stream.getFormat().getFrameSize();
            if (!Double.isFinite(duration) || duration <= 0 || duration > 300 || dataBytes > wav.length) {
                throw new IllegalStateException("CosyVoice returned an invalid or overlong WAV");
            }
            return duration;
        } catch (UnsupportedAudioFileException exception) {
            throw new IllegalStateException("CosyVoice returned an unsupported WAV", exception);
        }
    }
}
