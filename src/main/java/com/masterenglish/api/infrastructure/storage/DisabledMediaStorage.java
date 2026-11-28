package com.masterenglish.api.infrastructure.storage;

import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageDisabledException;
import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.storage.r2.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class DisabledMediaStorage implements MediaStorage {
    @Override
    public String bucket() {
        throw new StorageDisabledException("Media storage is disabled.");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void put(String key, Path file, String contentType, String sha256Checksum) {
        throw new StorageDisabledException("Media storage is disabled.");
    }

    @Override
    public void putIfAbsent(String key, Path file, String contentType, String sha256Checksum) {
        throw new StorageDisabledException("Media storage is disabled.");
    }

    @Override
    public InputStream openStream(String key) {
        throw new StorageDisabledException("Media storage is disabled.");
    }

    @Override
    public boolean delete(String key) {
        throw new StorageDisabledException("Media storage is disabled.");
    }

    @Override
    public boolean exists(String key) {
        throw new StorageDisabledException("Media storage is disabled.");
    }
}
