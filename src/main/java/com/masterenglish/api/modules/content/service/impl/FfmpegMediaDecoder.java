package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import com.masterenglish.api.modules.content.service.MediaDecoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FfmpegMediaDecoder implements MediaDecoder {

    public static final Set<String> SUPPORTED_IMAGE_MIMES =
            Set.of("image/jpeg", "image/png", "image/webp");
    public static final Set<String> SUPPORTED_AUDIO_MIMES = Set.of("audio/mpeg", "audio/mp4");

    public static final long MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024L; // 5 MiB
    public static final long MAX_AUDIO_SIZE_BYTES = 20 * 1024 * 1024L; // 20 MiB
    public static final int MAX_IMAGE_DIMENSION = 4096;
    public static final long MAX_IMAGE_PIXELS = 16_777_216L; // 16 MP (4096 x 4096)
    public static final double MAX_AUDIO_DURATION_SECONDS = 600.0; // 10 minutes
    public static final int MIN_AUDIO_SAMPLE_RATE = 8000;
    public static final int MAX_AUDIO_SAMPLE_RATE = 96000;
    public static final int MAX_AUDIO_CHANNELS = 2;

    public static final Duration DEFAULT_PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_STREAM_OUTPUT_BYTES = 1024 * 1024; // 1 MiB

    private final String ffprobeBinary;
    private final String ffmpegBinary;
    private final List<String> commandPrefix;
    private final Function<Path, String> pathTranslator;
    private final Duration processTimeout;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;

    @Autowired
    public FfmpegMediaDecoder(
            @Value("${app.media.ffmpeg.probe-path:ffprobe}") String ffprobeBinary,
            @Value("${app.media.ffmpeg.decode-path:ffmpeg}") String ffmpegBinary,
            ObjectMapper objectMapper) {
        this(
                ffprobeBinary,
                ffmpegBinary,
                List.of(),
                p -> p.toAbsolutePath().toString(),
                DEFAULT_PROCESS_TIMEOUT,
                objectMapper);
    }

    public FfmpegMediaDecoder(
            String ffprobeBinary,
            String ffmpegBinary,
            List<String> commandPrefix,
            Function<Path, String> pathTranslator,
            Duration processTimeout,
            ObjectMapper objectMapper) {
        this.ffprobeBinary =
                Objects.requireNonNull(ffprobeBinary, "ffprobeBinary must not be null");
        this.ffmpegBinary = Objects.requireNonNull(ffmpegBinary, "ffmpegBinary must not be null");
        this.commandPrefix = commandPrefix != null ? List.copyOf(commandPrefix) : List.of();
        this.pathTranslator =
                pathTranslator != null ? pathTranslator : p -> p.toAbsolutePath().toString();
        this.processTimeout = processTimeout != null ? processTimeout : DEFAULT_PROCESS_TIMEOUT;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public DecodedMediaInfo decode(Path stagedFile, String expectedMimeType) {
        // 1. Validate inputs and file system state
        if (stagedFile == null || !Files.isRegularFile(stagedFile)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Staged file not found or is not a regular file.");
        }
        if (expectedMimeType == null || expectedMimeType.isBlank()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Expected MIME type is required.");
        }

        String normalizedMime = expectedMimeType.trim().toLowerCase(Locale.ROOT);
        boolean isImage = SUPPORTED_IMAGE_MIMES.contains(normalizedMime);
        boolean isAudio = SUPPORTED_AUDIO_MIMES.contains(normalizedMime);
        if (!isImage && !isAudio) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported media MIME type: " + expectedMimeType);
        }

        long fileSize;
        try {
            fileSize = Files.size(stagedFile);
        } catch (IOException e) {
            log.error("Failed to read staged file size", e);
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "Failed to read staged file attributes.");
        }

        if (fileSize == 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Media file is empty (0 bytes).");
        }
        if (isImage && fileSize > MAX_IMAGE_SIZE_BYTES) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE,
                    "Image file exceeds maximum allowed size of 5 MiB: " + fileSize + " bytes.");
        }
        if (isAudio && fileSize > MAX_AUDIO_SIZE_BYTES) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE,
                    "Audio file exceeds maximum allowed size of 20 MiB: " + fileSize + " bytes.");
        }

        // 2. Magic bytes pre-check
        validateMagicBytes(stagedFile, normalizedMime);

        String expectedDemuxer =
                switch (normalizedMime) {
                    case "image/jpeg" -> "jpeg_pipe";
                    case "image/png" -> "png_pipe";
                    case "image/webp" -> "webp_pipe";
                    case "audio/mpeg" -> "mp3";
                    case "audio/mp4" -> "mov";
                    default ->
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR,
                                    "Unsupported media type: " + normalizedMime);
                };

        // 3. Probe metadata using ffprobe with security protocol/demuxer constraints
        String translatedPath = pathTranslator.apply(stagedFile);
        List<String> probeArgs = new ArrayList<>(commandPrefix);
        probeArgs.add(ffprobeBinary);
        probeArgs.add("-v");
        probeArgs.add("error");
        probeArgs.add("-protocol_whitelist");
        probeArgs.add("file");
        probeArgs.add("-f");
        probeArgs.add(expectedDemuxer);
        if ("audio/mp4".equals(normalizedMime)) {
            probeArgs.add("-enable_drefs");
            probeArgs.add("0");
        }
        if (isAudio && "audio/mpeg".equals(normalizedMime)) {
            probeArgs.add("-count_packets");
        }
        if (isImage) {
            probeArgs.add("-count_frames");
        }
        probeArgs.add("-show_entries");
        probeArgs.add(
                "format=format_name,duration,size:stream=index,codec_name,codec_type,width,height,sample_rate,channels,nb_read_packets,nb_read_frames");
        probeArgs.add("-show_streams");
        probeArgs.add("-of");
        probeArgs.add("json");
        probeArgs.add(translatedPath);

        ProcessResult probeResult = runProcess(probeArgs, processTimeout);
        if (probeResult.exitCode() != 0) {
            log.warn(
                    "ffprobe probe rejected media file for mimeType={} with exitCode={}",
                    normalizedMime,
                    probeResult.exitCode());
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Media file failed format probing or is invalid/corrupted.");
        }

        JsonNode probeJson;
        try {
            probeJson = objectMapper.readTree(probeResult.stdout());
        } catch (Exception e) {
            log.warn("Failed to parse probe JSON output", e);
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Failed to parse media probe metadata.");
        }

        // 3. Enforce exactly one media stream and correct stream types
        JsonNode streamsNode = probeJson.path("streams");
        if (!streamsNode.isArray() || streamsNode.size() != 1) {
            int streamCount = streamsNode.isArray() ? streamsNode.size() : 0;
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Media must contain exactly one stream, found " + streamCount + " streams.");
        }

        JsonNode streamNode = streamsNode.get(0);
        JsonNode formatNode = probeJson.path("format");
        String formatName = formatNode.path("format_name").asText("");
        String codecName = streamNode.path("codec_name").asText("").toLowerCase(Locale.ROOT);
        String codecType = streamNode.path("codec_type").asText("").toLowerCase(Locale.ROOT);

        Integer width = null;
        Integer height = null;
        Double duration = null;
        Integer sampleRate = null;
        Integer channels = null;

        if (isImage) {
            if (!"video".equals(codecType)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Expected image stream but detected stream type: " + codecType);
            }

            switch (normalizedMime) {
                case "image/jpeg" -> {
                    if (!"mjpeg".equals(codecName)) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "JPEG image must use mjpeg codec, detected: " + codecName);
                    }
                    if (!matchesDemuxer(formatName, Set.of("image2", "jpeg_pipe", "mjpeg"))) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "Invalid format for JPEG image: " + formatName);
                    }
                }
                case "image/png" -> {
                    if (!"png".equals(codecName)) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "PNG image must use png codec, detected: " + codecName);
                    }
                    if (!matchesDemuxer(formatName, Set.of("image2", "png_pipe", "png"))) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "Invalid format for PNG image: " + formatName);
                    }
                }
                case "image/webp" -> {
                    if (!"webp".equals(codecName)) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "WebP image must use webp codec, detected: " + codecName);
                    }
                    if (!matchesDemuxer(formatName, Set.of("image2", "webp_pipe", "webp"))) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "Invalid format for WebP image: " + formatName);
                    }
                }
                default ->
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "Unsupported image MIME type: " + normalizedMime);
            }

            width = streamNode.path("width").asInt(0);
            height = streamNode.path("height").asInt(0);
            if (width <= 0 || height <= 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Invalid image dimensions: " + width + "x" + height);
            }
            if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                throw new BusinessException(
                        ErrorCode.PAYLOAD_TOO_LARGE,
                        String.format(
                                Locale.ROOT,
                                "Image dimensions (%dx%d) exceed maximum allowed dimension (%dpx).",
                                width,
                                height,
                                MAX_IMAGE_DIMENSION));
            }
            long totalPixels = (long) width * height;
            if (totalPixels > MAX_IMAGE_PIXELS) {
                throw new BusinessException(
                        ErrorCode.PAYLOAD_TOO_LARGE,
                        String.format(
                                Locale.ROOT,
                                "Image pixel count (%d) exceeds maximum allowed limit (%d pixels).",
                                totalPixels,
                                MAX_IMAGE_PIXELS));
            }

            int frames = streamNode.path("nb_read_frames").asInt(0);
            if (frames > 1) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Multi-frame or animated images are not allowed, found "
                                + frames
                                + " frames.");
            }
            int packets = streamNode.path("nb_read_packets").asInt(0);
            if (packets > 1) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Multi-frame or animated images are not allowed, found "
                                + packets
                                + " packets.");
            }
        } else {
            // Audio
            if (!"audio".equals(codecType)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Expected audio stream but detected stream type: " + codecType);
            }

            switch (normalizedMime) {
                case "audio/mpeg" -> {
                    if (!"mp3".equals(codecName)) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "MP3 audio must use mp3 codec, detected: " + codecName);
                    }
                    if (!matchesDemuxer(formatName, Set.of("mp3"))) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "Invalid format for MP3 audio: " + formatName);
                    }
                }
                case "audio/mp4" -> {
                    if (!"aac".equals(codecName) && !"alac".equals(codecName)) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "M4A audio must use aac or alac codec, detected: " + codecName);
                    }
                    if (!formatName.contains("mov")
                            && !formatName.contains("mp4")
                            && !formatName.contains("m4a")) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "Invalid format for M4A audio: " + formatName);
                    }
                }
                default ->
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "Unsupported audio MIME type: " + normalizedMime);
            }

            channels = streamNode.path("channels").asInt(0);
            if (channels < 1 || channels > MAX_AUDIO_CHANNELS) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Audio channels must be 1 or 2, detected: " + channels);
            }

            sampleRate = streamNode.path("sample_rate").asInt(0);
            if (sampleRate < MIN_AUDIO_SAMPLE_RATE || sampleRate > MAX_AUDIO_SAMPLE_RATE) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        String.format(
                                Locale.ROOT,
                                "Audio sample rate (%d Hz) out of allowed bounds (%d-%d Hz).",
                                sampleRate,
                                MIN_AUDIO_SAMPLE_RATE,
                                MAX_AUDIO_SAMPLE_RATE));
            }

            double declaredDuration = formatNode.path("duration").asDouble(0.0);
            if (declaredDuration > MAX_AUDIO_DURATION_SECONDS) {
                throw new BusinessException(
                        ErrorCode.PAYLOAD_TOO_LARGE,
                        String.format(
                                Locale.ROOT,
                                "Audio declared duration (%.2fs) exceeds maximum allowed limit (%.0fs).",
                                declaredDuration,
                                MAX_AUDIO_DURATION_SECONDS));
            }
        }

        // 4. Full decode verification via ffmpeg with strict error detection
        List<String> decodeArgs = new ArrayList<>(commandPrefix);
        decodeArgs.add(ffmpegBinary);
        decodeArgs.add("-v");
        decodeArgs.add("error");
        decodeArgs.add("-err_detect");
        decodeArgs.add("explode");
        decodeArgs.add("-xerror");
        decodeArgs.add("-protocol_whitelist");
        decodeArgs.add("file");
        if ("audio/mp4".equals(normalizedMime)) {
            decodeArgs.add("-enable_drefs");
            decodeArgs.add("0");
        }
        decodeArgs.add("-threads");
        decodeArgs.add("2");
        decodeArgs.add("-f");
        decodeArgs.add(expectedDemuxer);
        decodeArgs.add("-i");
        decodeArgs.add(translatedPath);
        decodeArgs.add("-f");
        decodeArgs.add("null");
        decodeArgs.add("-");

        ProcessResult decodeResult = runProcess(decodeArgs, processTimeout);
        if (decodeResult.exitCode() != 0) {
            log.warn(
                    "ffmpeg decode rejected media file for mimeType={} with exitCode={}",
                    normalizedMime,
                    decodeResult.exitCode());
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Media file decoding failed or file is corrupted/truncated.");
        }

        // 5. Check duration and detect truncation for audio
        if (isAudio) {
            double formatDuration = formatNode.path("duration").asDouble(0.0);
            if ("audio/mpeg".equals(normalizedMime)) {
                int nbReadPackets = streamNode.path("nb_read_packets").asInt(0);
                if (nbReadPackets > 0) {
                    // MPEG-1 Layer 3 has 1152 samples/frame (>= 32000 Hz).
                    // MPEG-2 and MPEG-2.5 Layer 3 have 576 samples/frame (< 32000 Hz, e.g. 22050
                    // Hz).
                    int samplesPerFrame = sampleRate < 32000 ? 576 : 1152;
                    double packetDuration = nbReadPackets * ((double) samplesPerFrame / sampleRate);
                    if (formatDuration > 0.0) {
                        // Compare declared header duration (Xing/VBRI/TLEN) with actual decoded
                        // packets
                        double diff = Math.abs(formatDuration - packetDuration);
                        if (diff > 0.20 && (diff / formatDuration) > 0.05) {
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR,
                                    String.format(
                                            Locale.ROOT,
                                            "MP3 file is truncated: decoded duration (%.2fs) differs from header duration (%.2fs).",
                                            packetDuration,
                                            formatDuration));
                        }
                    }
                    duration = packetDuration;
                } else if (formatDuration > 0.0) {
                    duration = formatDuration;
                } else {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Could not determine audio duration from stream.");
                }
            } else {
                // M4A / AAC / ALAC
                if (formatDuration <= 0.0) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Could not determine audio duration from stream.");
                }
                duration = formatDuration;
            }

            if (duration <= 0.0 || duration > MAX_AUDIO_DURATION_SECONDS) {
                throw new BusinessException(
                        ErrorCode.PAYLOAD_TOO_LARGE,
                        String.format(
                                Locale.ROOT,
                                "Audio duration (%.2fs) exceeds maximum allowed limit (%.0fs).",
                                duration,
                                MAX_AUDIO_DURATION_SECONDS));
            }
        }

        return new DecodedMediaInfo(
                normalizedMime, codecName, width, height, duration, sampleRate, channels);
    }

    private static boolean matchesDemuxer(String formatName, Set<String> allowedDemuxers) {
        if (formatName == null || formatName.isBlank()) {
            return false;
        }
        String[] tokens = formatName.split(",");
        for (String token : tokens) {
            if (allowedDemuxers.contains(token.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private ProcessResult runProcess(List<String> command, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("Failed to execute process: {}", command.get(0));
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Media decoder binary not available or failed to execute.");
        }

        Future<String> stdoutFuture =
                streamExecutor.submit(
                        () -> readBoundedStream(process.getInputStream(), MAX_STREAM_OUTPUT_BYTES));
        Future<String> stderrFuture =
                streamExecutor.submit(
                        () -> readBoundedStream(process.getErrorStream(), MAX_STREAM_OUTPUT_BYTES));

        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly().onExit().join();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "Media processing was interrupted.");
        }

        if (!completed) {
            process.destroyForcibly().onExit().join();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Media processing timed out.");
        }

        int exitCode = process.exitValue();
        String stdout = getFutureOutput(stdoutFuture);
        String stderr = getFutureOutput(stderrFuture);

        return new ProcessResult(exitCode, stdout, stderr);
    }

    private static String readBoundedStream(InputStream is, int maxBytes) throws IOException {
        try (is) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int read;
            while ((read = is.read(buf)) != -1) {
                int toWrite = Math.min(read, maxBytes - total);
                if (toWrite > 0) {
                    baos.write(buf, 0, toWrite);
                    total += toWrite;
                }
                if (total >= maxBytes) {
                    break;
                }
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String getFutureOutput(Future<String> future) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static void validateMagicBytes(Path file, String mime) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(12);
            if (header.length < 4) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "File too small to contain valid media header.");
            }
            switch (mime) {
                case "image/jpeg" -> {
                    if ((header[0] & 0xFF) != 0xFF
                            || (header[1] & 0xFF) != 0xD8
                            || (header[2] & 0xFF) != 0xFF) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "Invalid JPEG magic bytes.");
                    }
                }
                case "image/png" -> {
                    byte[] pngMagic =
                            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
                    if (header.length < 8) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "Invalid PNG magic bytes.");
                    }
                    for (int i = 0; i < 8; i++) {
                        if (header[i] != pngMagic[i]) {
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR, "Invalid PNG magic bytes.");
                        }
                    }
                }
                case "image/webp" -> {
                    if (header.length < 12
                            || header[0] != 'R'
                            || header[1] != 'I'
                            || header[2] != 'F'
                            || header[3] != 'F'
                            || header[8] != 'W'
                            || header[9] != 'E'
                            || header[10] != 'B'
                            || header[11] != 'P') {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "Invalid WebP magic bytes.");
                    }
                }
                case "audio/mpeg" -> {
                    boolean isId3 = header[0] == 'I' && header[1] == 'D' && header[2] == '3';
                    boolean isSync = (header[0] & 0xFF) == 0xFF && ((header[1] & 0xE0) == 0xE0);
                    if (!isId3 && !isSync) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "Invalid MP3 magic bytes.");
                    }
                }
                case "audio/mp4" -> {
                    if (header.length < 8
                            || header[4] != 'f'
                            || header[5] != 't'
                            || header[6] != 'y'
                            || header[7] != 'p') {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "Invalid M4A/MP4 ftyp magic bytes.");
                    }
                }
                default -> {}
            }
        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Failed to inspect file magic bytes: " + e.getMessage());
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
