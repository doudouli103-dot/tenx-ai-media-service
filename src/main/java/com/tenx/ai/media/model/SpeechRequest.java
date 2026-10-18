package com.tenx.ai.media.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.regex.Pattern;

/** Media-only options; never forwarded to the frame-generation gateway. */
public class SpeechRequest {
    private final ObjectNode payload;

    private SpeechRequest(ObjectNode payload) { this.payload = payload; }

    public static SpeechRequest parse(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("audio must be an object");
        if (node.has("dialogues")) {
            if (node.has("text")) throw new IllegalArgumentException("Use either audio.text or audio.dialogues, not both");
            JsonNode lines = node.path("dialogues");
            if (!lines.isArray() || lines.size() < 1 || lines.size() > 30) {
                throw new IllegalArgumentException("audio.dialogues must contain 1-30 lines");
            }
            ObjectNode payload = ((ObjectNode) node).objectNode();
            payload.put("model", "cosyvoice2").put("response_format", "wav");
            ArrayNode dialogues = payload.putArray("dialogues");
            int total = 0;
            for (JsonNode line : lines) {
                if (!line.isObject()) throw new IllegalArgumentException("Each dialogue must be an object");
                if (line.has("dialogues")) throw new IllegalArgumentException("Nested dialogues are not supported");
                String speaker = text(line, "speaker", "");
                if (speaker.length() > 20) throw new IllegalArgumentException("Dialogue speaker exceeds 20 characters");
                String words = text(line, "text", "");
                if (!speaker.isEmpty()) words = words.replaceFirst("^" + Pattern.quote(speaker) + "\\s*[:：]\\s*", "");
                if (words.length() >= 2 && ((words.startsWith("“") && words.endsWith("”")) || (words.startsWith("\"") && words.endsWith("\"")))) {
                    words = words.substring(1, words.length() - 1).trim();
                }
                ObjectNode normalized = ((ObjectNode) line).deepCopy();
                normalized.remove("speaker");
                normalized.remove("pause_ms");
                normalized.put("text", words);
                SpeechRequest parsed = parse(normalized);
                JsonNode pause = line.get("pause_ms");
                if (pause != null && (!pause.isIntegralNumber() || !pause.canConvertToInt()
                        || pause.intValue() < 0 || pause.intValue() > 5000)) {
                    throw new IllegalArgumentException("pause_ms must be an integer between 0 and 5000");
                }
                ObjectNode entry = parsed.getPayload();
                entry.remove("input");
                entry.remove("model");
                entry.remove("response_format");
                entry.remove("seed");
                entry.put("text", parsed.getText()).put("speaker", speaker)
                        .put("pause_ms", pause == null ? 300 : pause.intValue());
                dialogues.add(entry);
                total += parsed.getText().length();
            }
            if (total > 500) throw new IllegalArgumentException("Total dialogue text exceeds 500 characters");
            return new SpeechRequest(payload);
        }
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
    public String getText() {
        if (!payload.has("dialogues")) return payload.path("input").asText();
        StringBuilder text = new StringBuilder();
        for (JsonNode line : payload.path("dialogues")) {
            if (text.length() > 0) text.append('\n');
            text.append(line.path("text").asText());
        }
        return text.toString();
    }
    public String getVoice() { return payload.has("dialogues") ? "dialogue" : payload.path("voice").asText(); }
    public JsonNode getDialogues() { return payload.has("dialogues") ? payload.path("dialogues").deepCopy() : null; }
}
