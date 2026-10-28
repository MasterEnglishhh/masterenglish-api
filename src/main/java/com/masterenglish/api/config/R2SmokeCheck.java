package com.masterenglish.api.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Explicit command-line check; never runs automatically at application startup. */
public final class R2SmokeCheck {
    static final byte[] CONTENT =
            "MasterEnglish R2 connection check\n".getBytes(StandardCharsets.UTF_8);

    private R2SmokeCheck() {}

    public static void main(String[] args) {
        try {
            var env = System.getenv();
            var properties =
                    new R2Properties(
                            true,
                            env.get("R2_ENDPOINT"),
                            env.getOrDefault("R2_REGION", "auto"),
                            env.get("R2_BUCKET"),
                            env.get("R2_ACCESS_KEY_ID"),
                            env.get("R2_SECRET_ACCESS_KEY"));
            try (var client = R2Configuration.createClient(properties)) {
                String key = verifyStorage(client, properties.bucket());
                System.out.println("R2 write/read/delete check passed: " + key);
            }
        } catch (Exception error) {
            // Keep credentials, SDK request details and signed URLs out of logs.
            System.err.println(
                    "R2 check failed ("
                            + error.getClass().getSimpleName()
                            + "). Check endpoint, bucket and scoped credentials on the server.");
            System.exit(1);
        }
    }

    static String verifyStorage(S3Client client, String bucket) {
        String key = "_checks/" + UUID.randomUUID() + ".txt";
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("text/plain")
                            .build(),
                    RequestBody.fromBytes(CONTENT));
            byte[] downloaded =
                    client.getObjectAsBytes(
                                    GetObjectRequest.builder().bucket(bucket).key(key).build())
                            .asByteArray();
            if (!Arrays.equals(CONTENT, downloaded)) {
                throw new IllegalStateException("R2 round-trip content mismatch.");
            }
        } finally {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        }
        return key;
    }
}
