package com.tenx.ai.media;

import com.tenx.ai.media.auth.ApiKeyWebFilter;
import com.tenx.ai.media.config.MediaProperties;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.test.web.reactive.server.WebTestClient;

public class ApiKeyWebFilterTest {

    @Test
    public void protectsApiButAllowsHealthCheck() {
        MediaProperties properties = new MediaProperties();
        properties.setApiKeys(Collections.singletonList("test-key"));
        WebTestClient client = WebTestClient.bindToRouterFunction(
                        RouterFunctions.route().GET("/healthz", request -> ServerResponse.ok().build())
                                .GET("/api/v1/test", request -> ServerResponse.ok().build()).build())
                .webFilter(new ApiKeyWebFilter(properties)).build();

        client.get().uri("/healthz").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/test").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/test").header("Authorization", "Bearer test-key")
                .exchange().expectStatus().isEqualTo(HttpStatus.OK);
    }
}
