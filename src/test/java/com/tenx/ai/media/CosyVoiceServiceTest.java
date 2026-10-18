package com.tenx.ai.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.SpeechAudio;
import com.tenx.ai.media.model.SpeechRequest;
import com.tenx.ai.media.service.CosyVoiceService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class CosyVoiceServiceTest {
    @TempDir Path temporary;
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String JOB = "0123456789abcdef0123456789abcdef";
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @AfterEach public void stop() { if (server != null) server.stop(0); }

    @Test public void downloadsAuthenticatedWavAndMeasuresDuration() throws Exception {
        byte[] wav = wav(1.25);
        CosyVoiceService service = runtime(200, wav);
        SpeechAudio result = service.generate(JOB, request());
        assertEquals(1.25, result.getDurationSeconds(), 0.001);
        assertEquals(JOB, result.getId());
        assertArrayEquals(wav, Files.readAllBytes(result.getPath()));
        assertEquals("Bearer test-speech-key", authorization.get());
        assertEquals("你好，世界。", received.get().path("input").asText());
        assertEquals("cosyvoice2", received.get().path("model").asText());
        assertEquals("wav", received.get().path("response_format").asText());
        assertFalse(Files.exists(temporary.resolve(JOB + ".part.wav")));
    }

    @Test public void rejectsBusyResponseWithoutSavingAudio() throws Exception {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime(429, new byte[0]).generate(JOB, request()));
        assertTrue(error.getMessage().contains("HTTP 429"));
        assertFalse(Files.exists(temporary.resolve(JOB + ".wav")));
    }

    @Test public void rejectsNonWavResponse() throws Exception {
        assertThrows(IllegalStateException.class, () -> runtime(200, new byte[100]).generate(JOB, request()));
        assertFalse(Files.exists(temporary.resolve(JOB + ".wav")));
    }

    @Test public void validatesSpeechOptions() throws Exception {
        assertNull(SpeechRequest.parse(null));
        for (String json : new String[] {"[]", "{}", "{\"text\":\"!!!\"}",
                "{\"text\":\"你好\",\"speed\":3}", "{\"text\":\"你好\",\"speed\":\"1\"}",
                "{\"text\":\"你好\",\"voice\":\"../demo\"}", "{\"text\":\"你好\",\"seed\":-1}"}) {
            JsonNode node = mapper.readTree(json);
            assertThrows(IllegalArgumentException.class, () -> SpeechRequest.parse(node), json);
        }
        assertThrows(IllegalArgumentException.class, () -> SpeechRequest.parse(
                mapper.createObjectNode().put("text", new String(new char[501]).replace('\0', 'a'))));
    }

    @Test public void parsesDialogueWithoutSpeakingLabelsOrRemovingSpokenNames() throws Exception {
        JsonNode node = mapper.readTree("{\"dialogues\":[{\"speaker\":\"小雨\",\"text\":\"小雨：你好。\",\"voice\":\"xiaoyu\",\"instructions\":\"轻声说。\",\"pause_ms\":400},"
                + "{\"speaker\":\"小雨\",\"text\":\"阿零，你胸口为什么在发光？\",\"voice\":\"robot\",\"pause_ms\":0}]}");
        SpeechRequest parsed = SpeechRequest.parse(node);
        assertFalse(parsed.getPayload().has("input"));
        assertEquals("你好。", parsed.getDialogues().get(0).path("text").asText());
        assertEquals("轻声说。", parsed.getDialogues().get(0).path("instructions").asText());
        assertEquals(400, parsed.getDialogues().get(0).path("pause_ms").asInt());
        assertTrue(parsed.getDialogues().get(1).path("text").asText().startsWith("阿零，"));
        assertEquals("dialogue", parsed.getVoice());
    }

    @Test public void rejectsInvalidDialoguePayloads() throws Exception {
        for (String json : new String[] {"{\"dialogues\":[]}", "{\"text\":\"你好\",\"dialogues\":[{\"text\":\"你好\"}]}",
                "{\"dialogues\":[{\"text\":\"你好\",\"pause_ms\":5001}]}",
                "{\"dialogues\":[{\"text\":\"你好\",\"pause_ms\":2.5}]}",
                "{\"dialogues\":[{\"text\":\"你好\",\"voice\":\"../bad\"}]}",
                "{\"dialogues\":[{\"text\":\"你好\",\"dialogues\":[]}]}",
                "{\"dialogues\":[{\"speaker\":\"小雨\",\"text\":\"小雨：\"}]}"}) {
            JsonNode request = mapper.readTree(json);
            assertThrows(IllegalArgumentException.class, () -> SpeechRequest.parse(request), json);
        }
    }

    @Test public void sendsDialoguePayloadToRuntimeInOrder() throws Exception {
        CosyVoiceService service = runtime(200, wav(2));
        SpeechRequest request = SpeechRequest.parse(mapper.readTree("{\"dialogues\":[{\"speaker\":\"小雨\",\"text\":\"你好。\",\"voice\":\"xiaoyu\"},{\"speaker\":\"阿零\",\"text\":\"我来啦。\",\"voice\":\"robot\"}]}"));
        service.generate(JOB, request);
        assertFalse(received.get().has("input"));
        assertEquals("xiaoyu", received.get().path("dialogues").get(0).path("voice").asText());
        assertEquals("robot", received.get().path("dialogues").get(1).path("voice").asText());
    }

    private SpeechRequest request() { return SpeechRequest.parse(mapper.createObjectNode().put("text", "你好，世界。")); }

    private CosyVoiceService runtime(int status, byte[] response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/speech", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.getResponseHeaders().set("X-Audio-Id", JOB);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        MediaProperties properties = new MediaProperties();
        properties.setAudioRoot(temporary.toString());
        properties.setCosyvoiceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCosyvoiceApiKey("test-speech-key");
        return new CosyVoiceService(properties);
    }

    static byte[] wav(double seconds) throws Exception {
        byte[] pcm = new byte[(int) (seconds * 24000) * 2];
        for (int index = 0; index < pcm.length / 2; index++) {
            short sample = (short) (6000 * Math.sin(2 * Math.PI * 440 * index / 24000));
            pcm[index * 2] = (byte) sample;
            pcm[index * 2 + 1] = (byte) (sample >> 8);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(24000, 16, 1, true, false), pcm.length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output);
        }
        return output.toByteArray();
    }
}
