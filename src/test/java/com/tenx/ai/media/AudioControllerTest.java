package com.tenx.ai.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenx.ai.media.api.AudioController;
import com.tenx.ai.media.api.MediaExceptionHandler;
import com.tenx.ai.media.auth.ApiKeyWebFilter;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.SpeechAudio;
import com.tenx.ai.media.model.SpeechRequest;
import com.tenx.ai.media.service.CosyVoiceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;

public class AudioControllerTest {
    @TempDir Path temporary;

    @Test public void exposesAuthenticatedVoiceListAndWavPreview() throws Exception {
        CosyVoiceService speech = mock(CosyVoiceService.class);
        when(speech.voices()).thenReturn(Mono.just(new ObjectMapper().readTree("{\"voices\":[{\"id\":\"demo\",\"name\":\"demo\"}]}")));
        byte[] wav = CosyVoiceServiceTest.wav(1);
        Path path = temporary.resolve("test.wav");
        Files.write(path, wav);
        when(speech.generate(anyString(), any(SpeechRequest.class))).thenReturn(new SpeechAudio(path, null, 1));
        WebTestClient client = client(speech);
        client.get().uri("/api/v1/audio/voices").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/audio/voices").header("Authorization", "Bearer test-key")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.voices[0].id").isEqualTo("demo");
        client.post().uri("/api/v1/audio/speech").header("Authorization", "Bearer test-key")
                .header("Content-Type", "application/json").bodyValue("{\"dialogues\":[{\"speaker\":\"小雨\",\"text\":\"你好。\"}]}")
                .exchange().expectStatus().isOk().expectHeader().contentType("audio/wav").expectBody()
                .consumeWith(result -> org.junit.jupiter.api.Assertions.assertArrayEquals(wav, result.getResponseBody()));
        verify(speech).validateVoices(any(SpeechRequest.class));
    }

    @Test public void rejectsBadPreviewWithoutGeneratingAudio() {
        CosyVoiceService speech = mock(CosyVoiceService.class);
        client(speech).post().uri("/api/v1/audio/speech").header("Authorization", "Bearer test-key")
                .header("Content-Type", "application/json").bodyValue("{\"dialogues\":[]}")
                .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(speech);
    }

    private WebTestClient client(CosyVoiceService speech) {
        MediaProperties properties = new MediaProperties();
        properties.setApiKeys(Collections.singletonList("test-key"));
        return WebTestClient.bindToController(new AudioController(speech))
                .controllerAdvice(new MediaExceptionHandler()).webFilter(new ApiKeyWebFilter(properties)).build();
    }
}
