package com.tenx.ai.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.DramaRequest;
import com.tenx.ai.media.model.JobStatus;
import com.tenx.ai.media.model.MediaJob;
import com.tenx.ai.media.model.SpeechAudio;
import com.tenx.ai.media.model.SpeechRequest;
import com.tenx.ai.media.service.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.web.reactive.function.client.WebClient;

public class DramaJobServiceTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode plan() {
        ObjectNode plan = mapper.createObjectNode().put("model", "Wan2.2-TI2V-5B").put("character_prompt", "red robot");
        ArrayNode shots = plan.putArray("shots");
        for (int i = 0; i < 6; i++) shots.addObject().put("prompt", "action-" + i).put("duration", 5)
                .put("continue_previous", i == 1 || i == 2 || i == 4);
        return plan;
    }

    @Test
    public void validatesBoundedPlanAndReferenceRules() {
        assertEquals(6, DramaRequest.validate(plan()).path("shots").size());
        ObjectNode invalid = plan();
        ((ObjectNode) invalid.path("shots").get(0)).put("continue_previous", true);
        assertThrows(IllegalArgumentException.class, () -> DramaRequest.validate(invalid));
        ObjectNode longShot = plan();
        ((ObjectNode) longShot.path("shots").get(0)).put("duration", 20);
        assertThrows(IllegalArgumentException.class, () -> DramaRequest.validate(longShot));
        ObjectNode model = plan().put("model", "HunyuanVideo-1.5");
        assertThrows(IllegalArgumentException.class, () -> DramaRequest.validate(model));
        ObjectNode conflict = plan();
        ((ObjectNode) conflict.path("shots").get(1)).put("start_image_result_id", "tenx_0123456789abcdef0123456789abcdef_00001_");
        assertThrows(IllegalArgumentException.class, () -> DramaRequest.validate(conflict));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void serialGenerationResumesAndInvalidatesOnlyDependentShots() throws Exception {
        MediaProperties properties = new MediaProperties();
        properties.setResultsRoot(root.toString());
        MediaJobRepository repository = new MediaJobRepository(mapper, properties);
        repository.load();
        VideoJobService videos = mock(VideoJobService.class);
        FfmpegRunner ffmpeg = mock(FfmpegRunner.class);
        List<ObjectNode> requests = new ArrayList<>();
        AtomicBoolean failSecond = new AtomicBoolean(true);
        when(videos.generateInline(any(ObjectNode.class), anyString(), anyInt(), any(Consumer.class)))
                .thenAnswer(call -> {
                    ObjectNode request = ((ObjectNode) call.getArgument(0)).deepCopy();
                    requests.add(request);
                    MediaJob child = MediaJob.video(UUID.randomUUID().toString().replace("-", ""), "Wan2.2-TI2V-5B");
                    child.setParentId(call.getArgument(1));
                    child.setGatewayResultId("tenx_" + child.getId());
                    ((Consumer<MediaJob>) call.getArgument(3)).accept(child);
                    if ("shot-2".equals(request.path("shot_id").asText()) && failSecond.getAndSet(false)) {
                        child.setError("test failure"); child.transition(JobStatus.FAILED);
                    } else child.transition(JobStatus.COMPLETED);
                    repository.save(child);
                    return child;
                });
        DramaJobService service = new DramaJobService(repository, videos, mock(CosyVoiceService.class), ffmpeg, properties, Runnable::run);
        MediaJob parent = service.submit(plan());
        assertEquals(JobStatus.FAILED, parent.getStatus());
        assertEquals(2, requests.size());
        String first = parent.getDramaShots().get(0).getId();
        service.retry(parent.getId(), null);
        assertEquals(JobStatus.COMPLETED, parent.getStatus());
        assertEquals(7, requests.size()); // first completed shot is not repeated
        assertEquals(first, parent.getDramaShots().get(0).getId());
        assertEquals(parent.getDramaShots().get(0).getGatewayResultId(), requests.get(2).path("start_video_result_id").asText());
        assertTrue(requests.get(3).path("prompt").asText().startsWith("red robot\n"));
        assertFalse(requests.get(4).has("start_video_result_id")); // independent third boundary
        String independent = parent.getDramaShots().get(3).getId();
        service.retry(parent.getId(), 0);
        assertEquals(10, requests.size()); // only 0 -> 1 -> 2 regenerated
        assertEquals(independent, parent.getDramaShots().get(3).getId());
        verify(ffmpeg, times(2)).concatenate(eq(parent.getId()), anyList());
        ObjectNode edit = ((ObjectNode) parent.getDramaRequest().path("shots").get(3)).deepCopy();
        edit.put("prompt", "new camera angle").put("duration", 3);
        service.retry(parent.getId(), 3, edit);
        assertEquals(12, requests.size()); // edit 3 invalidates 3 and its dependent 4, not 5
        assertEquals(28.0, parent.getDynamicDurationSeconds());
        assertTrue(requests.get(10).path("prompt").asText().contains("new camera angle"));
        String completedId = parent.getDramaShots().get(3).getId();
        edit.put("duration", 20);
        assertThrows(IllegalArgumentException.class, () -> service.retry(parent.getId(), 3, edit));
        assertEquals(JobStatus.COMPLETED, parent.getStatus());
        assertEquals(completedId, parent.getDramaShots().get(3).getId());
        MediaJobRepository reloaded = new MediaJobRepository(mapper, properties);
        reloaded.load();
        assertEquals(6, reloaded.find(parent.getId()).get().getDramaShots().size());
        assertEquals(JobStatus.COMPLETED, reloaded.find(parent.getId()).get().getStatus());
    }

    @Test
    public void rejectsDuplicateRetryAndRetainsQueuedPlanOnRestart() throws Exception {
        MediaProperties properties = new MediaProperties(); properties.setResultsRoot(root.toString());
        MediaJobRepository repository = new MediaJobRepository(mapper, properties); repository.load();
        List<Runnable> queued = new ArrayList<>();
        DramaJobService service = new DramaJobService(repository, mock(VideoJobService.class), mock(CosyVoiceService.class),
                mock(FfmpegRunner.class), properties, queued::add);
        MediaJob parent = service.submit(plan());
        assertEquals(1, queued.size());
        assertThrows(IllegalArgumentException.class, () -> service.retry(parent.getId(), 0));
        MediaJobRepository restarted = new MediaJobRepository(mapper, properties); restarted.load();
        assertEquals(JobStatus.FAILED, restarted.find(parent.getId()).get().getStatus());
        assertEquals(6, restarted.find(parent.getId()).get().getDramaRequest().path("shots").size());
    }

    @Test
    public void validatesAllVoicesBeforeGeneratingAnyShot() throws Exception {
        MediaProperties properties = new MediaProperties(); properties.setResultsRoot(root.toString());
        MediaJobRepository repository = new MediaJobRepository(mapper, properties); repository.load();
        CosyVoiceService voices = mock(CosyVoiceService.class);
        doThrow(new IllegalArgumentException("Unknown voice")).when(voices).validateVoices(any(SpeechRequest.class));
        VideoJobService videos = mock(VideoJobService.class);
        DramaJobService service = new DramaJobService(repository, videos, voices, mock(FfmpegRunner.class), properties, Runnable::run);
        ObjectNode plan = plan();
        ((ObjectNode) plan.path("shots").get(5)).putObject("audio").put("text", "hello").put("voice", "missing");
        MediaJob parent = service.submit(plan);
        assertEquals(JobStatus.FAILED, parent.getStatus());
        verifyNoInteractions(videos);
    }

    @Test
    public void overlongAudioFailsBeforeGatewayFrames() throws Exception {
        MediaProperties properties = new MediaProperties(); properties.setResultsRoot(root.toString());
        MediaJobRepository repository = new MediaJobRepository(mapper, properties); repository.load();
        CosyVoiceService voices = mock(CosyVoiceService.class);
        when(voices.generate(anyString(), any(SpeechRequest.class))).thenReturn(new SpeechAudio(root.resolve("test.wav"), "test", 6.0));
        WebClient gateway = mock(WebClient.class);
        FfmpegRunner ffmpeg = mock(FfmpegRunner.class);
        VideoJobService service = new VideoJobService(gateway, properties, repository, Runnable::run, ffmpeg, mock(FrameDownloader.class), voices);
        ObjectNode request = mapper.createObjectNode().put("model", "Wan2.2-TI2V-5B").put("prompt", "fight").put("duration", 5);
        request.putObject("audio").put("text", "test voice");
        MediaJob child = service.generateInline(request, "parent", 5, job -> { });
        assertEquals(JobStatus.FAILED, child.getStatus());
        assertTrue(child.getError().contains("Shorten dialogue"));
        verifyNoInteractions(gateway, ffmpeg);
    }
}
