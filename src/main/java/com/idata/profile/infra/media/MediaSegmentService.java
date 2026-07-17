package com.idata.profile.infra.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MediaSegmentService {

    @Value("${media.processing.ffmpeg-path:D:/ffmpeg-7.0/bin/ffmpeg.exe}")
    private String ffmpegPath;

    @Value("${media.processing.ffprobe-path:D:/ffmpeg-7.0/bin/ffprobe.exe}")
    private String ffprobePath;

    @Value("${media.processing.segment-seconds:30}")
    private int segmentSeconds;

    @Value("${media.processing.max-segments:20}")
    private int maxSegments;

    @Value("${media.processing.process-timeout-seconds:180}")
    private int processTimeoutSeconds;

    public Path extractAudio(String mediaSource) {
        if (!hasText(mediaSource)) {
            return null;
        }
        try {
            Path audioFile = Files.createTempFile("profile-media-audio-", ".wav");
            int exit = run(List.of(
                    ffmpegPath,
                    "-y",
                    "-i", mediaSource,
                    "-vn",
                    "-ac", "1",
                    "-ar", "16000",
                    "-f", "wav",
                    audioFile.toString()));
            return exit == 0 && Files.isRegularFile(audioFile) ? audioFile : null;
        } catch (Exception e) {
            log.warn("[MediaSegmentService] extract audio failed, source={}", mediaSource, e);
            return null;
        }
    }

    public List<VideoSegmentFrame> extractVideoSegmentFrames(String mediaSource, Integer fallbackDurationSeconds) {
        if (!hasText(mediaSource)) {
            return List.of();
        }
        int duration = resolveDurationSeconds(mediaSource, fallbackDurationSeconds);
        if (duration <= 0) {
            duration = Math.max(segmentSeconds, segmentSeconds * Math.max(1, maxSegments));
        }

        List<VideoSegmentFrame> frames = new ArrayList<>();
        int safeSegmentSeconds = Math.max(1, segmentSeconds);
        int safeMaxSegments = Math.max(1, maxSegments);
        for (int start = 0, index = 0; start < duration && index < safeMaxSegments;
             start += safeSegmentSeconds, index++) {
            int end = Math.min(start + safeSegmentSeconds, duration);
            int timestamp = start + Math.max(0, Math.min(2, Math.max(0, end - start - 1)));
            Path frame = extractFrame(mediaSource, timestamp, index);
            if (frame != null) {
                frames.add(new VideoSegmentFrame("seg_" + index, start, end, frame));
            }
        }
        return frames;
    }

    public void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("[MediaSegmentService] failed to delete temp file, file={}", file, e);
        }
    }

    private Path extractFrame(String mediaSource, int timestampSeconds, int index) {
        try {
            Path frameFile = Files.createTempFile("profile-media-frame-" + index + "-", ".jpg");
            int exit = run(List.of(
                    ffmpegPath,
                    "-y",
                    "-ss", String.valueOf(Math.max(0, timestampSeconds)),
                    "-i", mediaSource,
                    "-frames:v", "1",
                    "-q:v", "2",
                    frameFile.toString()));
            return exit == 0 && Files.isRegularFile(frameFile) ? frameFile : null;
        } catch (Exception e) {
            log.warn("[MediaSegmentService] extract frame failed, source={}, timestamp={}",
                    mediaSource, timestampSeconds, e);
            return null;
        }
    }

    private int resolveDurationSeconds(String mediaSource, Integer fallbackDurationSeconds) {
        Double probed = probeDuration(mediaSource);
        if (probed != null && probed > 0) {
            return (int) Math.ceil(probed);
        }
        return fallbackDurationSeconds == null ? 0 : Math.max(0, fallbackDurationSeconds);
    }

    private Double probeDuration(String mediaSource) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    mediaSource);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            boolean finished = process.waitFor(Math.max(1, processTimeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || !hasText(output)) {
                return null;
            }
            return Double.parseDouble(output.trim());
        } catch (Exception e) {
            log.debug("[MediaSegmentService] probe duration failed, source={}", mediaSource, e);
            return null;
        }
    }

    private int run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(Math.max(1, processTimeoutSeconds), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("[MediaSegmentService] command timeout after {}, command={}",
                    Duration.ofSeconds(processTimeoutSeconds), command);
            return -1;
        }
        int exit = process.exitValue();
        if (exit != 0) {
            log.warn("[MediaSegmentService] command failed, exit={}, command={}, output={}",
                    exit, command, output);
        }
        return exit;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record VideoSegmentFrame(String segmentId, float segmentStart, float segmentEnd, Path frameFile) {
    }
}
