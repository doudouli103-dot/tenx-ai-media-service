package com.tenx.ai.media.auth;

import com.tenx.ai.media.config.MediaProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class ApiKeyWebFilter implements WebFilter {

    private final MediaProperties properties;

    public ApiKeyWebFilter(MediaProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if ("/healthz".equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : "";
        boolean accepted = properties.getApiKeys().stream()
                .filter(key -> key != null && !key.isEmpty())
                .anyMatch(key -> constantTimeEquals(key, supplied));
        if (!accepted) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
