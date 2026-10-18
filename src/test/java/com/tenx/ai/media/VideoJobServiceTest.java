package com.tenx.ai.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.JobStatus;
import com.tenx.ai.media.model.MediaJob;
import com.tenx.ai.media.model.SpeechAudio;
import com.tenx.ai.media.model.SpeechRequest;
import com.tenx.ai.media.service.FfmpegRunner;
import com.tenx.ai.media.service.CosyVoiceService;
import com.tenx.ai.media.service.FrameDownloader;
import com.tenx.ai.media.service.MediaJobRepository;
import com.tenx.ai.media.service.VideoJobService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

public class VideoJobServiceTest {

    @TempDir Path temporary;
    private HttpServer server;

    @AfterEach
    public void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    public void generatesFramesAndEncodesFinalVideo() throws Exception {
        assertPipeline(false, false);
    }

    @Test
    public void unknownVoiceIsRejectedBeforeCallingGateway() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MediaProperties properties = new MediaProperties();
        properties.setResultsRoot(temporary.resolve("rejected").toString());
        MediaJobRepository repository = new MediaJobRepository(mapper, properties);
        repository.load();
        CosyVoiceService speech = Mockito.mock(CosyVoiceService.class);
        Mockito.doThrow(new IllegalArgumentException("Unknown voice: missing")).when(speech)
                .validateVoices(Mockito.any(SpeechRequest.class));
        WebClient gateway = Mockito.mock(WebClient.class);
        VideoJobService service = new VideoJobService(gateway, properties, repository, Runnable::run,
                Mockito.mock(FfmpegRunner.class), Mockito.mock(FrameDownloader.class), speech);
        ObjectNode request = mapper.createObjectNode().put("model", "Wan2.2-TI2V-5B").put("prompt", "test");
        request.putObject("audio").putArray("dialogues").addObject().put("speaker", "小雨").put("text", "你好").put("voice", "missing");
        MediaJob job = service.submit(request);
        Assertions.assertEquals(JobStatus.FAILED, job.getStatus());
        Assertions.assertTrue(job.getError().contains("Unknown voice"));
        Assertions.assertEquals(1, job.getAudioDialogues().size());
        Mockito.verifyNoInteractions(gateway);
    }

    @Test
    public void generatesSpeechAndPassesItToEncoder() throws Exception {
        assertPipeline(true, false);
    }

    @Test
    public void speechFailureFailsJobInsteadOfReturningSilentVideo() throws Exception {
        assertPipeline(true, true);
    }

    private void assertPipeline(boolean withAudio, boolean speechFailure) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path resultsRoot = temporary.resolve("results");
        Path frameCache = temporary.resolve("frame-cache");
        String gatewayResultId = "tenx_0123456789abcdef0123456789abcdef";
        String relative = "tenx-video-frames/" + gatewayResultId;
        ArrayNode frameFiles = mapper.createArrayNode();
        for (int index = 1; index <= 25; index++) {
            String name = String.format(Locale.ROOT, "frame_%05d_.png", index);
            frameFiles.add(name);
        }

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("status", "frames_ready");
        manifest.put("output_type", "image_sequence");
        manifest.put("result_id", gatewayResultId);
        manifest.put("frames_relative_path", relative);
        manifest.put("frame_pattern", "frame_%05d_.png");
        manifest.put("frame_count", 25);
        manifest.put("fps", 24);
        manifest.put("seed", 123456L);
        manifest.put("steps", 14);
        manifest.put("workflow_version", "short-drama-v1");
        manifest.put("reference_mode", "start_frame");
        manifest.set("frame_files", frameFiles);

        AtomicReference<String> authorization = new AtomicReference<String>();
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> gatewayRequest = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        manifest.put("frames_base_url", "http://macstudio.tentest.cn:8088"
                + "/v1/videos/results/" + gatewayResultId + "/frames");
        server.createContext("/v1/videos/generations", exchange -> {
            gatewayRequest.set(mapper.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = mapper.writeValueAsBytes(manifest);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/videos/results/" + gatewayResultId + "/frames/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1};
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        MediaProperties properties = new MediaProperties();
        properties.setGatewayApiKey("gateway-key");
        properties.setGatewayBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setFrameCacheRoot(frameCache.toString());
        properties.setResultsRoot(resultsRoot.toString());
        properties.setPublicBaseUrl("http://windows.test:8092");
        MediaJobRepository repository = new MediaJobRepository(mapper, properties);
        repository.load();
        FfmpegRunner ffmpeg = Mockito.mock(FfmpegRunner.class);
        CosyVoiceService cosyvoice = Mockito.mock(CosyVoiceService.class);
        if (withAudio) {
            if (speechFailure) {
                Mockito.when(cosyvoice.generate(Mockito.anyString(), Mockito.any(SpeechRequest.class)))
                        .thenThrow(new IllegalStateException("CosyVoice unavailable"));
            } else {
                Mockito.when(cosyvoice.generate(Mockito.anyString(), Mockito.any(SpeechRequest.class)))
                        .thenAnswer(invocation -> new SpeechAudio(temporary.resolve("speech.wav"),
                                "0123456789abcdef0123456789abcdef", 2.5));
                Mockito.when(ffmpeg.encode(Mockito.anyString(), Mockito.any(Path.class), Mockito.eq(24),
                        Mockito.eq(25), Mockito.any(SpeechAudio.class))).thenAnswer(invocation -> {
                    SpeechAudio audio = invocation.getArgument(4);
                    Assertions.assertEquals(2.5, audio.getDurationSeconds());
                    Path video = resultsRoot.resolve("videos").resolve(invocation.getArgument(0) + ".mp4");
                    Files.createDirectories(video.getParent());
                    Files.write(video, new byte[] {0, 0, 0, 1});
                    return video;
                });
            }
        }
        Mockito.when(ffmpeg.encode(Mockito.anyString(), Mockito.any(Path.class), Mockito.eq(24)))
                .thenAnswer(invocation -> {
                    String jobId = invocation.getArgument(0);
                    Path downloaded = invocation.getArgument(1);
                    Assertions.assertEquals(frameCache.resolve(jobId), downloaded);
                    Assertions.assertTrue(Files.isRegularFile(downloaded.resolve("frame_00001_.png")));
                    Assertions.assertTrue(Files.isRegularFile(downloaded.resolve("frame_00025_.png")));
                    Path video = resultsRoot.resolve("videos").resolve(jobId + ".mp4");
                    Files.createDirectories(video.getParent());
                    Files.write(video, new byte[] {0, 0, 0, 1});
                    return video;
                });
        WebClient gateway = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/")
                .build();
        Executor direct = Runnable::run;
        VideoJobService service = new VideoJobService(gateway, properties, repository, direct, ffmpeg,
                new FrameDownloader(properties), cosyvoice);
        ObjectNode request = mapper.createObjectNode();
        request.put("model", "Wan2.2-TI2V-5B");
        request.put("prompt", "robot walking");
        request.put("duration", 1);
        request.put("project_id", "drama-001");
        request.put("scene_id", "scene-001");
        request.put("shot_id", "shot-001");
        request.put("start_image_result_id", "tenx_0123456789abcdef0123456789abcdef_00001_");
        if (withAudio) request.putObject("audio").put("text", "欢迎来到动漫世界。");

        MediaJob job = service.submit(request);

        Assertions.assertFalse(gatewayRequest.get().has("audio"));
        Assertions.assertEquals(withAudio, job.isAudioEnabled());
        if (!withAudio) Mockito.verifyNoInteractions(cosyvoice);
        if (speechFailure) {
            Assertions.assertEquals(JobStatus.FAILED, job.getStatus());
            Assertions.assertTrue(job.getError().contains("CosyVoice"));
            Mockito.verifyNoInteractions(ffmpeg);
            return;
        }
        if (withAudio) {
            Assertions.assertEquals("demo", job.getAudioVoice());
            Assertions.assertEquals(2.5, job.getAudioDurationSeconds());
            Assertions.assertEquals("欢迎来到动漫世界。", job.getAudioText());
        }

        Assertions.assertEquals(JobStatus.COMPLETED, job.getStatus());
        Assertions.assertEquals(25, job.getFrameCount().intValue());
        Assertions.assertEquals(relative, job.getFramesRelativePath());
        Assertions.assertEquals("drama-001", job.getProjectId());
        Assertions.assertEquals("scene-001", job.getSceneId());
        Assertions.assertEquals("shot-001", job.getShotId());
        Assertions.assertEquals(Long.valueOf(123456L), job.getSeed());
        Assertions.assertEquals(Integer.valueOf(14), job.getSteps());
        Assertions.assertEquals("short-drama-v1", job.getWorkflowVersion());
        Assertions.assertEquals("start_frame", job.getReferenceMode());
        Assertions.assertEquals("Bearer gateway-key", authorization.get());
        Assertions.assertTrue(Files.isRegularFile(frameCache.resolve(job.getId()).resolve("frame_00001_.png")));
        Assertions.assertEquals("http://windows.test:8092/api/v1/videos/results/" + job.getId() + "/content",
                job.getResultUrl());
        Assertions.assertTrue(Files.isRegularFile(resultsRoot.resolve("jobs").resolve(job.getId() + ".json")));
        Assertions.assertTrue(Files.exists(frameCache.resolve(job.getId())));
    }
}
