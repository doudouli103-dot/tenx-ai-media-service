package com.tenx.ai.media.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A bounded, immutable plan. Do not forward orchestration fields to the gateway. */
public final class DramaRequest {
    private DramaRequest() { }

    public static ObjectNode validate(JsonNode request) {
        if (request == null || !request.isObject()) throw new IllegalArgumentException("JSON object required");
        ObjectNode plan = ((ObjectNode) request).deepCopy();
        String model = plan.path("model").asText("");
        if (!"Wan2.2-TI2V-5B".equals(model)) throw new IllegalArgumentException("Drama mode currently requires Wan2.2-TI2V-5B");
        String size = plan.path("size").asText("640x352");
        if (!size.matches("[0-9]{3,4}x[0-9]{3,4}")) throw new IllegalArgumentException("Invalid size");
        String[] dimensions = size.split("x");
        int width = Integer.parseInt(dimensions[0]), height = Integer.parseInt(dimensions[1]);
        if (width < 256 || width > 1280 || height < 256 || height > 720 || width % 32 != 0 || height % 32 != 0) {
            throw new IllegalArgumentException("Unsupported drama resolution");
        }
        plan.put("size", size);
        String characters = text(plan, "character_prompt", 1000, false);
        plan.put("character_prompt", characters);
        validateImage(plan);
        JsonNode shots = plan.path("shots");
        if (!shots.isArray() || shots.size() < 2 || shots.size() > 12) throw new IllegalArgumentException("shots must contain 2-12 shots (up to 60 seconds)");
        for (int index = 0; index < shots.size(); index++) {
            JsonNode shot = shots.get(index);
            if (!shot.isObject()) throw new IllegalArgumentException("Each shot must be an object");
            text(shot, "prompt", 2000, true);
            JsonNode duration = shot.path("duration");
            if (!duration.isInt() || duration.intValue() < 1 || duration.intValue() > 5) throw new IllegalArgumentException("Shot duration must be an integer from 1 to 5 seconds");
            if (shot.has("continue_previous") && !shot.path("continue_previous").isBoolean()) throw new IllegalArgumentException("continue_previous must be boolean");
            if (shot.path("continue_previous").asBoolean() && (index == 0 || shot.hasNonNull("start_image_result_id"))) {
                throw new IllegalArgumentException("Tail continuation requires a preceding shot and cannot specify an image");
            }
            validateImage(shot);
            SpeechRequest.parse(shot.get("audio"));
        }
        return plan;
    }

    private static void validateImage(JsonNode node) {
        if (node.hasNonNull("start_image_result_id") && (!node.path("start_image_result_id").isTextual()
                || !node.path("start_image_result_id").asText().matches("tenx_[0-9a-f]{32}_[0-9]{5}_"))) {
            throw new IllegalArgumentException("Invalid start_image_result_id");
        }
    }

    private static String text(JsonNode node, String key, int max, boolean required) {
        if (node.has(key) && !node.path(key).isTextual()) throw new IllegalArgumentException(key + " must be text");
        String value = node.path(key).asText("").trim();
        if ((required && value.isEmpty()) || value.length() > max) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
}
