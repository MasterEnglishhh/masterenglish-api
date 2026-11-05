package com.masterenglish.api.config;

import com.masterenglish.api.common.storage.PronunciationStorage;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@ConditionalOnProperty(name = "app.storage.r2.enabled", havingValue = "true")
public class R2PronunciationStorage implements PronunciationStorage {
    private final R2Properties properties;
    private final S3Client client;
    private final S3Presigner presigner;

    public R2PronunciationStorage(R2Properties properties, S3Client client, S3Presigner presigner) {
        this.properties = properties;
        this.client = client;
        this.presigner = presigner;
    }

    @Override
    public String bucket() {
        return properties.bucket();
    }

    @Override
    public void put(String key, Path file, String checksum) {
        if (bucket().contains("backup"))
            throw new IllegalStateException("Use a media bucket for pronunciation.");
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket())
                        .key(key)
                        .contentType("audio/mpeg")
                        .cacheControl("private, max-age=3600")
                        .metadata(Map.of("sha256", checksum))
                        .build(),
                RequestBody.fromFile(file));
        var stored = client.headObject(b -> b.bucket(bucket()).key(key));
        try {
            if (stored.contentLength() != java.nio.file.Files.size(file)
                    || !checksum.equals(stored.metadata().get("sha256"))) {
                throw new IllegalStateException("R2 pronunciation verification failed.");
            }
            byte[] downloaded =
                    client.getObjectAsBytes(b -> b.bucket(bucket()).key(key)).asByteArray();
            String downloadedChecksum =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(downloaded));
            if (!checksum.equals(downloadedChecksum)) {
                throw new IllegalStateException("R2 pronunciation download checksum mismatch.");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot verify pronunciation file.", exception);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public URI signGet(String key) {
        return URI.create(
                presigner
                        .presignGetObject(
                                GetObjectPresignRequest.builder()
                                        .signatureDuration(Duration.ofMinutes(5))
                                        .getObjectRequest(
                                                GetObjectRequest.builder()
                                                        .bucket(bucket())
                                                        .key(key)
                                                        .build())
                                        .build())
                        .url()
                        .toString());
    }
}
