package com.tenx.ai.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.SpeechAudio;
import com.tenx.ai.media.service.FfmpegRunner;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class FfmpegAudioTest {
    @TempDir Path temporary;

    @Test public void extendsVideoWithoutCuttingLongSpeech() throws Exception { verifyMux(2.5); }
    @Test public void padsShortSpeechWithoutCuttingVideo() throws Exception { verifyMux(0.5); }

    private void verifyMux(double speechSeconds) throws Exception {
        assumeTool("ffmpeg");
        assumeTool("ffprobe");
        Path frames = temporary.resolve("frames");
        Files.createDirectories(frames);
        for (int index = 1; index <= 12; index++) {
            ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png",
                    frames.resolve(String.format(Locale.ROOT, "frame_%05d_.png", index)).toFile());
        }
        Path audio = temporary.resolve("speech.wav");
        Files.write(audio, CosyVoiceServiceTest.wav(speechSeconds));
        MediaProperties properties = new MediaProperties();
        properties.setResultsRoot(temporary.resolve("results").toString());
        Path video = new FfmpegRunner(properties).encode("0123456789abcdef0123456789abcdef", frames,
                12, 12, new SpeechAudio(audio, null, speechSeconds));
        assertTrue(Files.size(video) > 1000);
        JsonNode probe = probe(video, temporary.resolve("probe.json"));
        boolean hasVideo = false, hasAudio = false;
        for (JsonNode stream : probe.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) {
                hasVideo = true;
                assertEquals("h264", stream.path("codec_name").asText());
            }
            if ("audio".equals(stream.path("codec_type").asText())) {
                hasAudio = true;
                assertEquals("aac", stream.path("codec_name").asText());
            }
        }
        assertTrue(hasVideo && hasAudio);
        assertEquals(Math.max(1, speechSeconds), probe.path("format").path("duration").asDouble(), 0.15);
    }

    static JsonNode probe(Path video, Path output) throws Exception {
        Process process = new ProcessBuilder("ffprobe", "-v", "error", "-show_streams", "-show_format",
                "-of", "json", video.toString()).redirectOutput(output.toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return new ObjectMapper().readTree(output.toFile());
    }

    private void assumeTool(String name) throws Exception {
        try {
            Process process = new ProcessBuilder(name, "-version")
                    .redirectOutput(temporary.resolve(name + ".version.log").toFile()).start();
            Assumptions.assumeTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0);
        } catch (java.io.IOException exception) {
            Assumptions.assumeTrue(false, name + " is not installed");
        }
    }
}
