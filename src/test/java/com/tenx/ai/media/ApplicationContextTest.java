package com.tenx.ai.media;

import com.tenx.ai.media.config.MediaProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tenx.media.results-root=target/test-media")
public class ApplicationContextTest {

    @Autowired MediaProperties properties;

    @Test
    public void loadsWindowsDefaults() {
        Assertions.assertEquals(1, properties.getMaxConcurrentVideoJobs());
        Assertions.assertEquals("ffmpeg", properties.getFfmpegPath());
        Assertions.assertTrue(properties.getGatewayBaseUrl().endsWith("/v1"));
    }
}
