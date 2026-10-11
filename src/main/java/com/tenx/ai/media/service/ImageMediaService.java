package com.tenx.ai.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenx.ai.media.config.MediaProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ImageMediaService {

    private static final Pattern RESULT_ID = Pattern.compile("tenx_[0-9a-f]{32}_[0-9]{5}_");
    private final WebClient gateway;
    private final MediaProperties properties;
    private final ObjectMapper mapper;

    public ImageMediaService(WebClient gatewayWebClient, MediaProperties properties, ObjectMapper mapper) {
        this.gateway = gatewayWebClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    public Mono<JsonNode> generate(JsonNode request) {
        if (request == null || !request.isObject()) {
            return Mono.error(new IllegalArgumentException("JSON object request body is required"));
        }
        return gateway.post().uri("images/generations")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(properties.getGatewayTimeoutMillis()))
                .flatMap(this::copyResults);
    }

    private Mono<JsonNode> copyResults(JsonNode response) {
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() < 1 || data.size() > 4) {
            return Mono.error(new IllegalStateException("Gateway returned an invalid image result"));
        }
        return Flux.fromIterable(data)
                .concatMap(item -> copyOne(item.path("result_id").asText("")))
                .collectList()
                .map(items -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("created", response.path("created").asLong(System.currentTimeMillis() / 1000));
                    ArrayNode output = result.putArray("data");
                    items.forEach(output::add);
                    return (JsonNode) result;
                });
    }

    private Mono<ObjectNode> copyOne(String resultId) {
        if (!RESULT_ID.matcher(resultId).matches()) {
            return Mono.error(new IllegalStateException("Gateway returned an invalid image result_id"));
        }
        return gateway.get().uri("images/results/{id}/content", resultId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .bodyToMono(byte[].class)
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> saveImage(resultId, bytes));
    }

    private ObjectNode saveImage(String resultId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Gateway returned an empty image");
        }
        try {
            Path root = Paths.get(properties.getResultsRoot()).toAbsolutePath().normalize();
            Path images = root.resolve("images");
            Files.createDirectories(images);
            Path target = images.resolve(resultId + ".png");
            Path temporary = images.resolve(resultId + ".png.tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            ObjectNode item = mapper.createObjectNode();
            item.put("result_id", resultId);
            item.put("url", properties.getPublicBaseUrl().replaceAll("/+$", "")
                    + "/api/v1/images/results/" + resultId + "/content");
            return item;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot save generated image", exception);
        }
    }

    private String bearer() {
        return "Bearer " + (properties.getGatewayApiKey() == null ? "" : properties.getGatewayApiKey());
    }
}
