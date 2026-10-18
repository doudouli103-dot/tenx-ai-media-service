package com.tenx.ai.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenx.ai.media.config.MediaProperties;
import com.tenx.ai.media.model.SpeechAudio;
import com.tenx.ai.media.service.FfmpegRunner;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Synthetic moving frames + fixture WAV; does not claim to run the Mac video model. */
public class DramaFfmpegSmokeTest {
    @Test
    public void concatenatesSixMovingShotsIntoThirtySecondVideoWithAudio() throws Exception {
        String executable = System.getProperty("tenx.ffmpeg");
        assumeTrue(executable != null, "Set -Dtenx.ffmpeg=ffmpeg to run real encoding smoke test");
        Path root = Paths.get("target", "drama-smoke").toAbsolutePath();
        Files.createDirectories(root);
        MediaProperties properties = new MediaProperties();
        properties.setResultsRoot(root.toString()); properties.setFfmpegPath(executable);
        FfmpegRunner encoder = new FfmpegRunner(properties);
        Path wav = root.resolve("fixture.wav");
        Files.write(wav, CosyVoiceServiceTest.wav(1));
        List<String> ids = new ArrayList<>();
        for (int shot = 0; shot < 6; shot++) {
            Path directory = root.resolve("frames-" + shot); Files.createDirectories(directory);
            for (int frame = 1; frame <= 121; frame++) {
                BufferedImage image = new BufferedImage(320, 256, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.getHSBColor(shot / 6f, .7f, .4f)); graphics.fillRect(0, 0, 320, 256);
                graphics.setColor(Color.WHITE); graphics.fillRect((frame * 2) % 280, 90, 40, 40); graphics.dispose();
                ImageIO.write(image, "png", directory.resolve(String.format(Locale.ROOT, "frame_%05d_.png", frame)).toFile());
            }
            String id = UUID.randomUUID().toString().replace("-", ""); ids.add(id);
            encoder.encodeShot(id, directory, 24, 121, shot % 2 == 0 ? new SpeechAudio(wav, null, 1) : null, 5);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        Path video = encoder.concatenate(id, ids);
        Path report = root.resolve("probe.json");
        Process probe = new ProcessBuilder("ffprobe", "-v", "error", "-count_frames", "-show_streams", "-show_format", "-of", "json", video.toString())
                .redirectOutput(report.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertTrue(probe.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, probe.exitValue());
        JsonNode metadata = new ObjectMapper().readTree(report.toFile());
        assertEquals(30, metadata.path("format").path("duration").asDouble(), .2);
        assertEquals("h264", metadata.path("streams").get(0).path("codec_name").asText());
        assertEquals(720, metadata.path("streams").get(0).path("nb_read_frames").asInt());
        assertEquals("aac", metadata.path("streams").get(1).path("codec_name").asText());
        assertEquals(1, metadata.path("streams").get(1).path("channels").asInt());
        Files.write(root.resolve("latest-video.txt"), video.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Drama encoding smoke MP4: " + video);
    }
}
