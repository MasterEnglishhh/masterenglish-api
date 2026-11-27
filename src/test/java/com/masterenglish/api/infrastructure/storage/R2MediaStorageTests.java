package com.masterenglish.api.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageException;
import com.masterenglish.api.common.storage.StorageObjectNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Testcontainers
class R2MediaStorageTests {
    private static final String BUCKET = "masterenglish-media-test";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin123";

    @Container
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(
                            DockerImageName.parse("bitnamilegacy/minio:2025.7.23-debian-12-r3"))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withEnv("MINIO_DEFAULT_BUCKETS", BUCKET)
                    .waitingFor(
                            Wait.forHttp("/minio/health/ready")
                                    .forPort(9000)
                                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static S3Client s3Client;
    private static MediaStorage mediaStorage;

    @BeforeAll
    static void setUp() {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        s3Client =
                S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(true)
                                        .chunkedEncodingEnabled(false)
                                        .build())
                        .build();

        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
        }

        mediaStorage = new R2MediaStorage(BUCKET, s3Client);
    }

    @AfterAll
    static void tearDown() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    @Test
    void putAndOpenStream_readsExactBytes_andClosesStream(@TempDir Path tempDir) throws Exception {
        byte[] payload =
                "synthetic-media-content-for-testing-123456789".getBytes(StandardCharsets.UTF_8);
        Path staged = tempDir.resolve("sample.png");
        Files.write(staged, payload);

        String key = "content/images/sample.png";
        mediaStorage.put(key, staged, "image/png");

        assertThat(mediaStorage.exists(key)).isTrue();

        try (InputStream stream = mediaStorage.openStream(key)) {
            byte[] downloaded = stream.readAllBytes();
            assertThat(downloaded).isEqualTo(payload);
        }
    }

    @Test
    void putIfAbsent_keepsExistingBytes(@TempDir Path tempDir) throws Exception {
        Path staged = tempDir.resolve("input.bin");
        String key = "content/immutable.bin";
        Files.writeString(staged, "first");
        mediaStorage.putIfAbsent(key, staged, "image/png", null);
        Files.writeString(staged, "second");
        mediaStorage.putIfAbsent(key, staged, "image/png", null);
        try (InputStream stream = mediaStorage.openStream(key)) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("first");
        }
    }

    @Test
    void put_withMatchingSha256Checksum_succeeds(@TempDir Path tempDir) throws Exception {
        byte[] payload = "audio-media-bytes-checksum-test".getBytes(StandardCharsets.UTF_8);
        Path staged = tempDir.resolve("audio.mp3");
        Files.write(staged, payload);

        String sha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        String key = "content/audio/audio.mp3";

        mediaStorage.put(key, staged, "audio/mpeg", sha256);

        assertThat(mediaStorage.exists(key)).isTrue();
        try (InputStream stream = mediaStorage.openStream(key)) {
            assertThat(stream.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void put_withMismatchedSha256Checksum_throwsStorageException(@TempDir Path tempDir)
            throws Exception {
        byte[] payload = "audio-media-bytes-mismatch-test".getBytes(StandardCharsets.UTF_8);
        Path staged = tempDir.resolve("audio-bad.mp3");
        Files.write(staged, payload);

        String invalidChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String key = "content/audio/audio-bad.mp3";

        assertThatThrownBy(() -> mediaStorage.put(key, staged, "audio/mpeg", invalidChecksum))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void openStream_nonExistentKey_throwsStorageObjectNotFoundException() {
        assertThatThrownBy(() -> mediaStorage.openStream("content/missing/file.png"))
                .isInstanceOf(StorageObjectNotFoundException.class)
                .hasMessageContaining("Object not found in storage.");
    }

    @Test
    void exists_returnsFalseForMissingObject() {
        assertThat(mediaStorage.exists("content/non-existent-key.bin")).isFalse();
    }

    @Test
    void delete_existingObject_returnsTrue_andRemovesObject(@TempDir Path tempDir)
            throws Exception {
        Path staged = tempDir.resolve("to-delete.png");
        Files.write(staged, "temporary-content".getBytes(StandardCharsets.UTF_8));
        String key = "content/temp/to-delete.png";

        mediaStorage.put(key, staged, "image/png");
        assertThat(mediaStorage.exists(key)).isTrue();

        boolean deleted = mediaStorage.delete(key);
        assertThat(deleted).isTrue();
        assertThat(mediaStorage.exists(key)).isFalse();
    }

    @Test
    void delete_nonExistentObject_returnsFalseWithoutException() {
        boolean deleted = mediaStorage.delete("content/never-existed.png");
        assertThat(deleted).isFalse();
    }

    @Test
    void delete_storageError_throwsStorageExceptionWithoutFakeReceipt() {
        S3Client faultyClient = mock(S3Client.class);
        when(faultyClient.headObject(any(Consumer.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(100L).build());
        when(faultyClient.deleteObject(any(Consumer.class)))
                .thenThrow(
                        S3Exception.builder()
                                .message("Simulated S3 internal error")
                                .statusCode(500)
                                .build());

        MediaStorage faultStorage = new R2MediaStorage(BUCKET, faultyClient);

        assertThatThrownBy(() -> faultStorage.delete("content/item.bin"))
                .isInstanceOf(StorageException.class)
                .hasMessage("Storage delete operation failed.");
    }

    @Test
    void put_invalidKeys_rejected(@TempDir Path tempDir) throws Exception {
        Path staged = tempDir.resolve("file.png");
        Files.write(staged, "data".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> mediaStorage.put(null, staged, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mediaStorage.put("   ", staged, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mediaStorage.put("/leading-slash.png", staged, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mediaStorage.put("path/../traversal.png", staged, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void put_nonExistentStagedFile_throwsIllegalArgumentException() {
        Path nonExistent = Path.of("non-existent-staged-file-123.bin");
        assertThatThrownBy(() -> mediaStorage.put("content/file.bin", nonExistent, "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Staged file must be an existing regular file.");
    }

    @Test
    void put_blankContentType_throwsIllegalArgumentException(@TempDir Path tempDir)
            throws Exception {
        Path staged = tempDir.resolve("file.png");
        Files.write(staged, "data".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> mediaStorage.put("content/file.png", staged, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content type must not be blank.");
    }

    @Test
    void constructor_rejectsBlankOrBackupBucket() {
        assertThatThrownBy(() -> new R2MediaStorage(" ", s3Client))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new R2MediaStorage("masterenglish-backup-bucket", s3Client))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Use a media bucket for media storage.");
    }

    @Test
    void storageErrors_doNotLeakBucketName() {
        S3Client faultyClient = mock(S3Client.class);
        when(faultyClient.getObject(any(Consumer.class)))
                .thenThrow(
                        S3Exception.builder()
                                .message("Server error in bucket: " + BUCKET)
                                .statusCode(500)
                                .build());

        MediaStorage faultStorage = new R2MediaStorage(BUCKET, faultyClient);

        assertThatThrownBy(() -> faultStorage.openStream("content/key.bin"))
                .isInstanceOf(StorageException.class)
                .hasMessage("Storage read operation failed.")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(BUCKET));
    }
}
