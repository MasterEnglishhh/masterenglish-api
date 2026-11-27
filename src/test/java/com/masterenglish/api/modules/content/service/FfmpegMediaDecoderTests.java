package com.masterenglish.api.modules.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import com.masterenglish.api.modules.content.service.impl.FfmpegMediaDecoder;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class FfmpegMediaDecoderTests {

    private static final Path FIXTURES_DIR =
            Path.of("src/test/resources/fixtures/media").toAbsolutePath();

    @Container
    private static final GenericContainer<?> CONTAINER =
            new GenericContainer<>(
                            new ImageFromDockerfile("masterenglish-api:m08-runtime-test", false)
                                    .withFileFromPath("Dockerfile", Path.of("Dockerfile"))
                                    .withFileFromPath(
                                            "build/libs/app.jar", Path.of("build/libs/app.jar")))
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                    .withCommand("-c", "while true; do sleep 1; done");

    private static FfmpegMediaDecoder decoder;

    @BeforeAll
    static void setUp() {
        CONTAINER.copyFileToContainer(MountableFile.forHostPath(FIXTURES_DIR.toString()), "/media");

        decoder =
                new FfmpegMediaDecoder(
                        "ffprobe",
                        "ffmpeg",
                        List.of("docker", "exec", "-i", CONTAINER.getContainerId()),
                        path -> "/media/" + path.getFileName().toString(),
                        Duration.ofSeconds(15),
                        new ObjectMapper());
    }

    @Test
    void runtimeUsesBackendJarAndNonRootUser() throws Exception {
        var identity = CONTAINER.execInContainer("id", "-u");
        assertThat(identity.getExitCode()).isZero();
        assertThat(identity.getStdout().trim()).isEqualTo("10001");
        var jar = CONTAINER.execInContainer("sha256sum", "/app/app.jar");
        assertThat(jar.getExitCode()).isZero();
        String expected =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(Path.of("build/libs/app.jar"))));
        assertThat(jar.getStdout()).startsWith(expected + " ");
        for (String binary : List.of("ffmpeg", "ffprobe", "java")) {
            var version = CONTAINER.execInContainer(binary, "-version");
            assertThat(version.getExitCode()).isZero();
            System.out.println(binary + ": " + version.getStdout() + version.getStderr());
        }
        System.out.println("Backend runtime image: " + CONTAINER.getDockerImageName());
        System.out.println("Backend JAR SHA-256: " + expected);
    }

    @Test
    void nativeDecoderTimeoutStopsRealFfmpegInsideBackendImage() throws Exception {
        String classFile = NativeDecoderProcessCheck.class.getName().replace('.', '/') + ".class";
        Path classes =
                Path.of(
                        NativeDecoderProcessCheck.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        CONTAINER.copyFileToContainer(
                MountableFile.forHostPath(classes.resolve(classFile)), "/checks/" + classFile);
        var result =
                CONTAINER.execInContainer(
                        "java",
                        "-Duser.timezone=UTC",
                        "-Dloader.main=" + NativeDecoderProcessCheck.class.getName(),
                        "-Dloader.path=/checks",
                        "-cp",
                        "/app/app.jar",
                        "org.springframework.boot.loader.launch.PropertiesLauncher");
        assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
        assertThat(result.getStdout())
                .contains("NATIVE_JAVA_DECODER_FORMATS_CONFIRMED")
                .contains("NATIVE_FFMPEG_TIMEOUT_EXIT_CONFIRMED")
                .contains("NATIVE_FFMPEG_INTERRUPTION_EXIT_CONFIRMED");
        System.out.println(result.getStdout());
    }

    private Path fixture(String filename) {
        return FIXTURES_DIR.resolve(filename);
    }

    // --- 1. Valid Format Decoding (AC10 / AC11) ---

    @Test
    @DisplayName("Valid JPEG: correctly decodes mjpeg codec, dimensions 320x240, null duration")
    void decode_validJpeg_returnsCorrectMetadata() {
        DecodedMediaInfo info = decoder.decode(fixture("valid.jpg"), "image/jpeg");

        assertThat(info.mimeType()).isEqualTo("image/jpeg");
        assertThat(info.codec()).isEqualTo("mjpeg");
        assertThat(info.width()).isEqualTo(320);
        assertThat(info.height()).isEqualTo(240);
        assertThat(info.durationSeconds()).isNull();
        assertThat(info.sampleRate()).isNull();
        assertThat(info.channels()).isNull();
    }

    @Test
    @DisplayName("Valid PNG: correctly decodes png codec, dimensions 320x240, null duration")
    void decode_validPng_returnsCorrectMetadata() {
        DecodedMediaInfo info = decoder.decode(fixture("valid.png"), "image/png");

        assertThat(info.mimeType()).isEqualTo("image/png");
        assertThat(info.codec()).isEqualTo("png");
        assertThat(info.width()).isEqualTo(320);
        assertThat(info.height()).isEqualTo(240);
        assertThat(info.durationSeconds()).isNull();
    }

    @Test
    @DisplayName("Valid WebP: correctly decodes webp codec, dimensions 320x240, null duration")
    void decode_validWebp_returnsCorrectMetadata() {
        DecodedMediaInfo info = decoder.decode(fixture("valid.webp"), "image/webp");

        assertThat(info.mimeType()).isEqualTo("image/webp");
        assertThat(info.codec()).isEqualTo("webp");
        assertThat(info.width()).isEqualTo(320);
        assertThat(info.height()).isEqualTo(240);
        assertThat(info.durationSeconds()).isNull();
    }

    @Test
    @DisplayName("Valid MP3: correctly decodes mp3 codec, stereo 44100Hz, duration ~2.0s")
    void decode_validMp3_returnsCorrectMetadata() {
        DecodedMediaInfo info = decoder.decode(fixture("valid.mp3"), "audio/mpeg");

        assertThat(info.mimeType()).isEqualTo("audio/mpeg");
        assertThat(info.codec()).isEqualTo("mp3");
        assertThat(info.channels()).isEqualTo(2);
        assertThat(info.sampleRate()).isEqualTo(44100);
        assertThat(info.durationSeconds()).isBetween(1.9, 2.2);
        assertThat(info.width()).isNull();
        assertThat(info.height()).isNull();
    }

    @Test
    @DisplayName("Valid MP3 22.050Hz: correctly decodes mp3 codec, 22050Hz, duration ~2.0s")
    void decode_validMp3_22k_returnsCorrectMetadata() {
        DecodedMediaInfo info = decoder.decode(fixture("valid_22k.mp3"), "audio/mpeg");

        assertThat(info.mimeType()).isEqualTo("audio/mpeg");
        assertThat(info.codec()).isEqualTo("mp3");
        assertThat(info.sampleRate()).isEqualTo(22050);
        assertThat(info.durationSeconds()).isBetween(1.9, 2.2);
    }

    @Test
    @DisplayName("Valid M4A (AAC): correctly decodes aac codec, stereo 44100Hz, duration ~2.0s")
    void decode_validM4aAac_returnsCorrectMetadata() {
        DecodedMediaInfo info = decoder.decode(fixture("valid.m4a"), "audio/mp4");

        assertThat(info.mimeType()).isEqualTo("audio/mp4");
        assertThat(info.codec()).isEqualTo("aac");
        assertThat(info.channels()).isEqualTo(2);
        assertThat(info.sampleRate()).isEqualTo(44100);
        assertThat(info.durationSeconds()).isBetween(1.9, 2.2);
    }

    @Test
    @DisplayName("Valid M4A (ALAC): correctly decodes alac codec, stereo 44100Hz, duration ~2.0s")
    void decode_validM4aAlac_returnsCorrectMetadata() {
        DecodedMediaInfo info = decoder.decode(fixture("valid_alac.m4a"), "audio/mp4");

        assertThat(info.mimeType()).isEqualTo("audio/mp4");
        assertThat(info.codec()).isEqualTo("alac");
        assertThat(info.channels()).isEqualTo(2);
        assertThat(info.sampleRate()).isEqualTo(44100);
        assertThat(info.durationSeconds()).isBetween(1.9, 2.2);
    }

    // --- 2. Truncated Files Rejection (AC10 / AC27 / AC28) ---

    @Test
    @DisplayName("Truncated JPEG: rejected with VALIDATION_ERROR")
    void decode_truncatedJpeg_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("truncated.jpg"), "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Truncated PNG: rejected with VALIDATION_ERROR")
    void decode_truncatedPng_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("truncated.png"), "image/png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Truncated WebP: rejected with VALIDATION_ERROR")
    void decode_truncatedWebp_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("truncated.webp"), "image/webp"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Truncated MP3 (header chunk): rejected with VALIDATION_ERROR")
    void decode_truncatedMp3_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("truncated.mp3"), "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Truncated M4A: rejected with VALIDATION_ERROR")
    void decode_truncatedM4a_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("truncated.m4a"), "audio/mp4"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName(
            "Truncated Xing MP3: declared header duration (2.04s) differs from decoded frames (0.99s), rejected")
    void decode_truncatedXingMp3_detectsDiscrepancyAndRejects() {
        assertThatThrownBy(() -> decoder.decode(fixture("truncated_xing.mp3"), "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                            assertThat(be.getMessage()).contains("truncated");
                        });
    }

    // --- 3. Corrupt Files Rejection (AC10 / AC27 / AC28) ---

    @Test
    @DisplayName("Corrupt JPEG: rejected with VALIDATION_ERROR")
    void decode_corruptJpeg_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("corrupt.jpg"), "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Corrupt PNG: rejected with VALIDATION_ERROR")
    void decode_corruptPng_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("corrupt.png"), "image/png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Corrupt WebP: rejected with VALIDATION_ERROR")
    void decode_corruptWebp_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("corrupt.webp"), "image/webp"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Corrupt MP3: rejected with VALIDATION_ERROR")
    void decode_corruptMp3_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("corrupt.mp3"), "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Corrupt M4A: rejected with VALIDATION_ERROR")
    void decode_corruptM4a_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("corrupt.m4a"), "audio/mp4"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // --- 4. Fake and Wrong-Type Files Rejection (AC10 / AC11) ---

    @Test
    @DisplayName("Fake PNG (text disguised as PNG): rejected with VALIDATION_ERROR")
    void decode_fakePng_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("fake.png"), "image/png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Fake MP3 (text disguised as MP3): rejected with VALIDATION_ERROR")
    void decode_fakeMp3_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("fake.mp3"), "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Wrong Type (MP3 disguised as JPEG): rejected with VALIDATION_ERROR")
    void decode_wrongType_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("wrong_type.jpg"), "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // --- 5. Caps and Bounds Enforcement (AC27) ---

    @Test
    @DisplayName(
            "Pixel Bomb (5000x5000 = 25 MP > 16 MP): rejected with PAYLOAD_TOO_LARGE before decode")
    void decode_pixelBomb_rejectedBeforeRasterization() {
        assertThatThrownBy(() -> decoder.decode(fixture("pixel_bomb.png"), "image/png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                            assertThat(be.getMessage()).contains("exceed");
                        });
    }

    @Test
    @DisplayName("Empty file (0 bytes): rejected with VALIDATION_ERROR before starting process")
    void decode_emptyFile_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("empty.png"), "image/png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                            assertThat(be.getMessage()).contains("empty");
                        });
    }

    @Test
    @DisplayName("Image exceeding 5 MiB cap: rejected with PAYLOAD_TOO_LARGE")
    void decode_imageOverCap_rejected(@TempDir Path tempDir) throws Exception {
        Path bigFile = tempDir.resolve("big_image.jpg");
        try (RandomAccessFile raf = new RandomAccessFile(bigFile.toFile(), "rw")) {
            raf.setLength(5 * 1024 * 1024L + 1); // 5 MiB + 1 byte
        }

        assertThatThrownBy(() -> decoder.decode(bigFile, "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                            assertThat(be.getMessage()).contains("5 MiB");
                        });
    }

    @Test
    @DisplayName("Audio exceeding 20 MiB cap: rejected with PAYLOAD_TOO_LARGE")
    void decode_audioOverCap_rejected(@TempDir Path tempDir) throws Exception {
        Path bigAudio = tempDir.resolve("big_audio.mp3");
        try (RandomAccessFile raf = new RandomAccessFile(bigAudio.toFile(), "rw")) {
            raf.setLength(20 * 1024 * 1024L + 1); // 20 MiB + 1 byte
        }

        assertThatThrownBy(() -> decoder.decode(bigAudio, "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                            assertThat(be.getMessage()).contains("20 MiB");
                        });
    }

    @Test
    @DisplayName("Unsupported MIME type: rejected with VALIDATION_ERROR")
    void decode_unsupportedMime_rejected() {
        assertThatThrownBy(() -> decoder.decode(fixture("valid.jpg"), "application/pdf"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Non-existent staged file: rejected with VALIDATION_ERROR")
    void decode_nonExistentFile_rejected() {
        Path nonExistent = FIXTURES_DIR.resolve("does_not_exist.jpg");
        assertThatThrownBy(() -> decoder.decode(nonExistent, "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // --- 6. Failure Modes (Missing binary and Timeout) (AC10 / AC27) ---

    @Test
    @DisplayName("Missing binary: fails closed with INTERNAL_ERROR without crashing application")
    void decode_missingBinary_failsClosedWithInternalError() {
        FfmpegMediaDecoder missingBinaryDecoder =
                new FfmpegMediaDecoder(
                        "non_existent_ffprobe_binary_xyz",
                        "ffmpeg",
                        List.of(),
                        p -> p.toAbsolutePath().toString(),
                        Duration.ofSeconds(5),
                        new ObjectMapper());

        assertThatThrownBy(() -> missingBinaryDecoder.decode(fixture("valid.jpg"), "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("Concatenated multi-frame JPEG: rejected by jpeg_pipe with VALIDATION_ERROR")
    void decode_concatenatedMultiFrameJpeg_rejected(@TempDir Path tempDir) throws Exception {
        byte[] singleJpg = Files.readAllBytes(fixture("valid.jpg"));
        byte[] multiJpg = new byte[singleJpg.length * 2];
        System.arraycopy(singleJpg, 0, multiJpg, 0, singleJpg.length);
        System.arraycopy(singleJpg, 0, multiJpg, singleJpg.length, singleJpg.length);
        Path multiPath = tempDir.resolve("multi_frame.jpg");
        Files.write(multiPath, multiJpg);
        CONTAINER.copyFileToContainer(
                MountableFile.forHostPath(multiPath), "/media/multi_frame.jpg");

        assertThatThrownBy(() -> decoder.decode(multiPath, "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                            assertThat(be.getMessage()).contains("Multi-frame");
                        });
    }

    @Test
    @DisplayName("Process timeout: real OS child process is terminated and throws INTERNAL_ERROR")
    void decode_processTimeout_destroysRealProcessAndThrowsInternalError(@TempDir Path tempDir)
            throws Exception {
        Path pidFile = tempDir.resolve("child.pid");
        Path sleepScript = tempDir.resolve("SlowProbe.java");
        Files.writeString(
                sleepScript,
                """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class SlowProbe {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of("%s"), String.valueOf(ProcessHandle.current().pid()));
                        Thread.sleep(30_000);
                    }
                }
                """
                        .formatted(pidFile.toAbsolutePath().toString().replace("\\", "\\\\")));

        String javaBin =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElseThrow(() -> new IllegalStateException("Java binary not found"));
        FfmpegMediaDecoder timeoutDecoder =
                new FfmpegMediaDecoder(
                        sleepScript.toAbsolutePath().toString(),
                        "ffmpeg",
                        List.of(javaBin),
                        Path::toString,
                        Duration.ofSeconds(3),
                        new ObjectMapper());

        assertThatThrownBy(() -> timeoutDecoder.decode(fixture("valid.jpg"), "image/jpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                            assertThat(be.getMessage()).contains("timed out");
                        });

        assertThat(Files.exists(pidFile)).isTrue();
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        boolean stillAlive = ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
        assertThat(stillAlive).as("Real child process must exit after timeout").isFalse();
    }
}
