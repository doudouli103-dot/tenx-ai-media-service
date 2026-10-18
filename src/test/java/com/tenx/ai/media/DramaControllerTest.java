package com.tenx.ai.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.tenx.ai.media.api.DramaController;
import com.tenx.ai.media.api.MediaExceptionHandler;
import com.tenx.ai.media.auth.ApiKeyWebFilter;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.MediaJob;
import com.tenx.ai.media.service.DramaJobService;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.mockito.Mockito.*;

public class DramaControllerTest {
    @Test public void authenticatesSubmissionAndValidatesRetryIndex() {
        DramaJobService dramas = mock(DramaJobService.class);
        MediaJob job = MediaJob.video("0123456789abcdef0123456789abcdef", "Wan2.2-TI2V-5B"); job.setType("drama");
        when(dramas.submit(any(JsonNode.class))).thenReturn(job);
        when(dramas.retry(job.getId(), null)).thenReturn(job);
        MediaProperties properties = new MediaProperties(); properties.setApiKeys(Collections.singletonList("test-key"));
        WebTestClient client = WebTestClient.bindToController(new DramaController(dramas)).controllerAdvice(new MediaExceptionHandler())
                .webFilter(new ApiKeyWebFilter(properties)).build();
        client.post().uri("/api/v1/dramas/generations").bodyValue("{}").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/dramas/generations").header("Authorization", "Bearer test-key").header("Content-Type", "application/json")
                .bodyValue("{}").exchange().expectStatus().isAccepted().expectBody().jsonPath("$.type").isEqualTo("drama");
        client.post().uri("/api/v1/dramas/jobs/" + job.getId() + "/retry").header("Authorization", "Bearer test-key").header("Content-Type", "application/json")
                .bodyValue("{}").exchange().expectStatus().isAccepted();
        client.post().uri("/api/v1/dramas/jobs/" + job.getId() + "/retry").header("Authorization", "Bearer test-key").header("Content-Type", "application/json")
                .bodyValue("{\"shot_index\":1.5}").exchange().expectStatus().isBadRequest();
        verify(dramas).retry(job.getId(), null);
    }
}
