package com.tenx.ai.media.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Media-only options; never forwarded to the frame-generation gateway. */
public class SpeechRequest {
    private final ObjectNode payload;

    private SpeechRequest(ObjectNode payload) { this.payload = payload; }

    public static SpeechRequest parse(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("audio must be an object");
        String text = text(node, "text", "");
        if (text.isEmpty() || text.length() > 500 || !text.codePoints().anyMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException("audio.text must contain speech text (1-500 characters)");
        }
        String voice = text(node, "voice", "demo");
        if (!voice.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid audio.voice");
        String instructions = text(node, "instructions", "");
        if (instructions.length() > 200) throw new IllegalArgumentException("audio.instructions exceeds 200 characters");
        double speed = node.has("speed") ? node.path("speed").asDouble(Double.NaN) : 1.0;
        if ((node.has("speed") && !node.path("speed").isNumber()) || !Double.isFinite(speed) || speed < 0.5 || speed > 2) {
            throw new IllegalArgumentException("audio.speed must be between 0.5 and 2");
        }
        long seed = node.has("seed") ? node.path("seed").asLong(-1) : 42;
        if ((node.has("seed") && !node.path("seed").isIntegralNumber()) || seed < 0 || seed > 2147483647L) {
            throw new IllegalArgumentException("audio.seed must be an integer between 0 and 2147483647");
        }
        ObjectNode payload = ((ObjectNode) node).objectNode();
        payload.put("model", "cosyvoice2");
        payload.put("input", text);
        payload.put("voice", voice);
        payload.put("response_format", "wav");
        payload.put("speed", speed);
        payload.put("seed", seed);
        payload.put("instructions", instructions);
        return new SpeechRequest(payload);
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (!node.has(field)) return fallback;
        if (!node.path(field).isTextual()) throw new IllegalArgumentException("audio." + field + " must be text");
        return node.path(field).asText().trim();
    }

    public ObjectNode getPayload() { return payload.deepCopy(); }
    public String getText() { return payload.path("input").asText(); }
    public String getVoice() { return payload.path("voice").asText(); }
}
