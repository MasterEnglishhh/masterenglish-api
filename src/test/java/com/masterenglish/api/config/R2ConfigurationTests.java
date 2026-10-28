package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

class R2ConfigurationTests {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(R2Configuration.class);

    @Test
    void disabledStorageDoesNotRequireCredentialsOrCreateClients() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(S3Client.class);
                    assertThat(context).doesNotHaveBean(S3Presigner.class);
                });
    }

    @Test
    void enabledStorageWithMissingCredentialsFailsStartup() {
        runner.withPropertyValues("app.storage.r2.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void completeConfigurationBuildsClientsAndSignsPrivateObjectWithoutNetwork() {
        runner.withPropertyValues(
                        "app.storage.r2.enabled=true",
                        "app.storage.r2.endpoint=https://account.r2.cloudflarestorage.com",
                        "app.storage.r2.region=auto",
                        "app.storage.r2.bucket=masterenglish-media-production",
                        "app.storage.r2.access-key-id=fixture-access-key",
                        "app.storage.r2.secret-access-key=fixture-secret-key")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(S3Client.class);
                            var presigner = context.getBean(S3Presigner.class);
                            var signed =
                                    presigner.presignGetObject(
                                            GetObjectPresignRequest.builder()
                                                    .signatureDuration(Duration.ofMinutes(5))
                                                    .getObjectRequest(
                                                            GetObjectRequest.builder()
                                                                    .bucket(
                                                                            "masterenglish-media-production")
                                                                    .key("audio/lesson.mp3")
                                                                    .build())
                                                    .build());
                            assertThat(signed.url().getHost())
                                    .isEqualTo("account.r2.cloudflarestorage.com");
                            assertThat(signed.url().getPath())
                                    .isEqualTo("/masterenglish-media-production/audio/lesson.mp3");
                            assertThat(signed.url().getQuery()).contains("X-Amz-Signature=");
                            assertThat(context.getBean(R2Properties.class).toString())
                                    .doesNotContain("fixture-access-key", "fixture-secret-key");
                        });
    }

    @Test
    void rejectsInsecureEndpointsAndBucketPaths() {
        for (String endpoint :
                List.of(
                        "http://account.r2.cloudflarestorage.com",
                        "https://account.r2.cloudflarestorage.com/bucket",
                        "https://user:password@account.r2.cloudflarestorage.com",
                        "https://account.r2.cloudflarestorage.com?token=value")) {
            assertThatThrownBy(
                            () ->
                                    new R2Properties(
                                            true,
                                            endpoint,
                                            "auto",
                                            "valid-bucket",
                                            "fixture-key",
                                            "fixture-secret"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMissingCredentialsAndInvalidBucketNames() {
        assertThatThrownBy(
                        () ->
                                new R2Properties(
                                        true,
                                        "https://r2.example.com",
                                        "auto",
                                        "valid-bucket",
                                        "fixture-key",
                                        " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new R2Properties(
                                        true,
                                        "https://r2.example.com",
                                        "auto",
                                        "INVALID/BUCKET",
                                        "fixture-key",
                                        "fixture-secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disablesChunkedEncodingForR2SignatureCompatibility() {
        var configuration = R2Configuration.serviceConfiguration();
        assertThat(configuration.pathStyleAccessEnabled()).isTrue();
        assertThat(configuration.chunkedEncodingEnabled()).isFalse();
    }
}
