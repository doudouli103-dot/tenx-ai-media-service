package com.tenx.ai.media;

import com.tenx.ai.media.config.MediaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MediaProperties.class)
public class TenxAiMediaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenxAiMediaServiceApplication.class, args);
    }
}
