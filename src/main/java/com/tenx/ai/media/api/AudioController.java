package com.tenx.ai.media.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tenx.ai.media.model.SpeechRequest;
import com.tenx.ai.media.model.SpeechAudio;
import com.tenx.ai.media.service.CosyVoiceService;
import java.nio.file.Files;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/audio")
public class AudioController {
    private final CosyVoiceService speech;
    public AudioController(CosyVoiceService speech) { this.speech = speech; }

    @GetMapping("/voices")
    public Mono<JsonNode> voices() { return speech.voices(); }

    @PostMapping("/speech")
    public Mono<ResponseEntity<Resource>> preview(@RequestBody JsonNode request) {
        SpeechRequest parsed = SpeechRequest.parse(request);
        if (parsed == null) throw new IllegalArgumentException("Speech request is required");
        return Mono.<ResponseEntity<Resource>>fromCallable(() -> {
            speech.validateVoices(parsed);
            SpeechAudio audio = speech.generate(UUID.randomUUID().toString().replace("-", ""), parsed);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                    .contentLength(Files.size(audio.getPath()))
                    .header("Content-Disposition", "inline; filename=\"speech-preview.wav\"")
                    .body(new FileSystemResource(audio.getPath()));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
