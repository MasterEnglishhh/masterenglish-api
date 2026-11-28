package com.masterenglish.api.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageDisabledException;
import com.masterenglish.api.config.R2Configuration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

class DisabledMediaStorageTests {
    private final DisabledMediaStorage disabledStorage = new DisabledMediaStorage();

    @Test
    void isAvailable_returnsFalse() {
        assertThat(disabledStorage.isAvailable()).isFalse();
    }

    @Test
    void bucket_throwsStorageDisabledException() {
        assertThatThrownBy(disabledStorage::bucket)
                .isInstanceOf(StorageDisabledException.class)
                .hasMessage("Media storage is disabled.");
    }

    @Test
    void put_throwsStorageDisabledException() {
        assertThatThrownBy(() -> disabledStorage.put("key", Path.of("file"), "image/png", null))
                .isInstanceOf(StorageDisabledException.class)
                .hasMessage("Media storage is disabled.");
    }

    @Test
    void openStream_throwsStorageDisabledException() {
        assertThatThrownBy(() -> disabledStorage.openStream("key"))
                .isInstanceOf(StorageDisabledException.class)
                .hasMessage("Media storage is disabled.");
    }

    @Test
    void delete_throwsStorageDisabledException() {
        assertThatThrownBy(() -> disabledStorage.delete("key"))
                .isInstanceOf(StorageDisabledException.class)
                .hasMessage("Media storage is disabled.");
    }

    @Test
    void exists_throwsStorageDisabledException() {
        assertThatThrownBy(() -> disabledStorage.exists("key"))
                .isInstanceOf(StorageDisabledException.class)
                .hasMessage("Media storage is disabled.");
    }

    @Test
    void disabledConfiguration_providesDisabledMediaStorageBean_withoutS3Client() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        R2Configuration.class, DisabledMediaStorage.class, R2MediaStorage.class)
                .withPropertyValues("app.storage.r2.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(S3Client.class);
                            assertThat(context).hasSingleBean(MediaStorage.class);
                            MediaStorage storage = context.getBean(MediaStorage.class);
                            assertThat(storage).isInstanceOf(DisabledMediaStorage.class);
                            assertThat(storage.isAvailable()).isFalse();
                        });
    }

    @Test
    void enabledConfiguration_providesR2MediaStorageBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        R2Configuration.class, DisabledMediaStorage.class, R2MediaStorage.class)
                .withPropertyValues(
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
                            assertThat(context).hasSingleBean(MediaStorage.class);
                            MediaStorage storage = context.getBean(MediaStorage.class);
                            assertThat(storage).isInstanceOf(R2MediaStorage.class);
                            assertThat(storage.isAvailable()).isTrue();
                            assertThat(storage.bucket())
                                    .isEqualTo("masterenglish-media-production");
                        });
    }
}
