package com.masterenglish.api.infrastructure.storage;

import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageException;
import com.masterenglish.api.common.storage.StorageObjectNotFoundException;
import com.masterenglish.api.config.R2Properties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@ConditionalOnProperty(name = "app.storage.r2.enabled", havingValue = "true")
public class R2MediaStorage implements MediaStorage {
    private final String bucket;
    private final S3Client client;

    @Autowired
    public R2MediaStorage(R2Properties properties, S3Client client) {
        this(properties.bucket(), client);
    }

    public R2MediaStorage(String bucket, S3Client client) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("Bucket name must not be blank.");
        }
        if (bucket.contains("backup")) {
            throw new IllegalStateException("Use a media bucket for media storage.");
        }
        this.bucket = bucket;
        this.client = Objects.requireNonNull(client, "S3Client must not be null.");
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void put(String key, Path file, String contentType, String sha256Checksum) {
        put(key, file, contentType, sha256Checksum, false);
    }

    @Override
    public void putIfAbsent(String key, Path file, String contentType, String sha256Checksum) {
        put(key, file, contentType, sha256Checksum, true);
    }

    private void put(
            String key, Path file, String contentType, String sha256Checksum, boolean createOnly) {
        validateKey(key);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Staged file must be an existing regular file.");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Content type must not be blank.");
        }
        try {
            long fileSize = Files.size(file);
            if (sha256Checksum != null && !sha256Checksum.isBlank()) {
                String fileChecksum = calculateSha256(file);
                if (!sha256Checksum.equalsIgnoreCase(fileChecksum)) {
                    throw new StorageException("Stored object checksum mismatch.");
                }
            }
            var requestBuilder =
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .cacheControl("private, max-age=3600");
            if (sha256Checksum != null && !sha256Checksum.isBlank()) {
                requestBuilder.metadata(Map.of("sha256", sha256Checksum));
            }
            if (createOnly) {
                requestBuilder.ifNoneMatch("*");
            }
            try {
                client.putObject(requestBuilder.build(), RequestBody.fromFile(file));
            } catch (S3Exception e) {
                if (createOnly && e.statusCode() == 412) {
                    return;
                }
                throw e;
            }

            HeadObjectResponse head = client.headObject(b -> b.bucket(bucket).key(key));
            if (head.contentLength() != fileSize) {
                throw new StorageException("Stored object size mismatch.");
            }
            if (sha256Checksum != null && !sha256Checksum.isBlank()) {
                String storedChecksum = head.metadata().get("sha256");
                if (!sha256Checksum.equalsIgnoreCase(storedChecksum)) {
                    throw new StorageException("Stored object checksum mismatch.");
                }
            }
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException("Object not found after upload.", e);
        } catch (StorageException e) {
            throw e;
        } catch (S3Exception e) {
            throw new StorageException("Storage put operation failed.", e);
        } catch (SdkException e) {
            throw new StorageException("Storage client error during put.", e);
        } catch (IOException e) {
            throw new StorageException("Failed to access staged file.", e);
        }
    }

    private static String calculateSha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available.", e);
        }
    }

    @Override
    public InputStream openStream(String key) {
        validateKey(key);
        try {
            return client.getObject(b -> b.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException("Object not found in storage.", e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new StorageObjectNotFoundException("Object not found in storage.", e);
            }
            throw new StorageException("Storage read operation failed.", e);
        } catch (SdkException e) {
            throw new StorageException("Storage client error during read.", e);
        }
    }

    @Override
    public boolean exists(String key) {
        validateKey(key);
        try {
            client.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("Storage check operation failed.", e);
        } catch (SdkException e) {
            throw new StorageException("Storage client error during check.", e);
        }
    }

    @Override
    public boolean delete(String key) {
        validateKey(key);
        try {
            boolean existed;
            try {
                client.headObject(b -> b.bucket(bucket).key(key));
                existed = true;
            } catch (NoSuchKeyException e) {
                existed = false;
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    existed = false;
                } else {
                    throw new StorageException("Storage delete operation failed.", e);
                }
            }
            if (!existed) {
                return false;
            }
            client.deleteObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (S3Exception e) {
            throw new StorageException("Storage delete operation failed.", e);
        } catch (SdkException e) {
            throw new StorageException("Storage client error during delete.", e);
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) {
            throw new IllegalArgumentException("Invalid storage key.");
        }
    }
}
