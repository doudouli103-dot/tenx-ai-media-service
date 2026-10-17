package com.tenx.ai.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.JobStatus;
import com.tenx.ai.media.model.MediaJob;
import com.tenx.ai.media.service.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in test: real local CosyVoice and FFmpeg, synthetic Gateway frame fixture. */
@EnabledIfEnvironmentVariable(named = "TENX_COSYVOICE_SMOKE", matches = "true")
public class CosyVoicePipelineSmokeTest {
    @Test public void realSpeechIsAudibleInFinalMp4() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path root = Paths.get("target/cosyvoice-smoke").toAbsolutePath();
        MediaProperties properties = new MediaProperties();
        properties.setResultsRoot(root.toString());
        properties.setFrameCacheRoot(root.resolve("frames").toString());
        properties.setAudioRoot(System.getenv().getOrDefault("TENX_MEDIA_AUDIO_ROOT", root.resolve("audio").toString()));
        properties.setCosyvoiceBaseUrl(System.getenv().getOrDefault("TENX_COSYVOICE_BASE_URL", "http://127.0.0.1:4030"));
        properties.setCosyvoiceApiKey(System.getenv().getOrDefault("TENX_COSYVOICE_API_KEY", ""));
        String resultId = "tenx_0123456789abcdef0123456789abcdef";
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties.setGatewayBaseUrl("http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1");
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("status", "frames_ready").put("output_type", "image_sequence")
                .put("result_id", resultId).put("frames_relative_path", "tenx-video-frames/" + resultId)
                .put("frame_pattern", "frame_%05d_.png").put("frame_count", 1).put("fps", 12)
                .put("frames_base_url", properties.getGatewayBaseUrl() + "/videos/results/" + resultId + "/frames");
        manifest.putArray("frame_files").add("frame_00001_.png");
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 180; y++) for (int x = 0; x < 320; x++) image.setRGB(x, y, (x * 255 / 320 << 16) | (y * 255 / 180 << 8) | 90);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        gateway.createContext("/v1/videos/generations", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            assertFalse(request.has("audio"));
            byte[] response = mapper.writeValueAsBytes(manifest);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        gateway.createContext("/v1/videos/results/" + resultId + "/frames/", exchange -> {
            byte[] response = png.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        gateway.start();
        try {
            MediaJobRepository repository = new MediaJobRepository(mapper, properties);
            repository.load();
            VideoJobService service = new VideoJobService(WebClient.builder()
                    .baseUrl(properties.getGatewayBaseUrl() + "/").build(), properties, repository, Runnable::run,
                    new FfmpegRunner(properties), new FrameDownloader(properties), new CosyVoiceService(properties));
            ObjectNode request = mapper.createObjectNode().put("model", "Wan2.2-TI2V-5B").put("prompt", "test scene");
            request.putObject("audio").put("text", "你好，欢迎来到动漫世界。").put("voice", "demo");
            MediaJob job = service.submit(request);
            assertEquals(JobStatus.COMPLETED, job.getStatus(), job.getError());
            Path video = root.resolve("videos").resolve(job.getId() + ".mp4");
            JsonNode probe = FfmpegAudioTest.probe(video, root.resolve("probe.json"));
            boolean hasAudio = false;
            for (JsonNode stream : probe.path("streams")) {
                if ("audio".equals(stream.path("codec_type").asText())) {
                    hasAudio = true;
                    assertEquals("aac", stream.path("codec_name").asText());
                    assertTrue(stream.path("duration").asDouble() >= job.getAudioDurationSeconds() - 0.1);
                }
            }
            assertTrue(hasAudio);
            assertTrue(Files.isRegularFile(Paths.get(properties.getAudioRoot()).resolve(job.getId() + ".wav")));
            System.out.println("REAL_COSYVOICE_SMOKE_VIDEO=" + video);
            System.out.println("REAL_COSYVOICE_AUDIO_SECONDS=" + job.getAudioDurationSeconds());
        } finally { gateway.stop(0); }
    }
}
