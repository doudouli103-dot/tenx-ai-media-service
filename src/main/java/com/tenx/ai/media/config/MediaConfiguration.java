package com.tenx.ai.media.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class MediaConfiguration {

    @Bean
    public WebClient gatewayWebClient(MediaProperties properties) {
        int timeout = (int) Math.min(Integer.MAX_VALUE, properties.getGatewayTimeoutMillis());
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofMillis(properties.getGatewayTimeoutMillis()))
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(timeout, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(timeout, TimeUnit.MILLISECONDS)));
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
        return WebClient.builder()
                .baseUrl(properties.getGatewayBaseUrl().replaceAll("/+$", "") + "/")
                .clientConnector(new ReactorClientHttpConnector(client))
                .exchangeStrategies(strategies)
                .build();
    }

    @Bean
    @Qualifier("videoJobExecutor")
    public Executor videoJobExecutor(MediaProperties properties) {
        int concurrency = Math.max(1, properties.getMaxConcurrentVideoJobs());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("video-job-");
        executor.initialize();
        return executor;
    }
}
