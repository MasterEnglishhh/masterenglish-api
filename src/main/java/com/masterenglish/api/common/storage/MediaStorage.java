package com.masterenglish.api.common.storage;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Storage port for media objects. Implementations manage private storage access and must never leak
 * credentials, presigned write permissions, or raw storage errors to external callers.
 */
public interface MediaStorage {
    String bucket();

    boolean isAvailable();

    default void put(String key, Path file, String contentType) {
        put(key, file, contentType, null);
    }

    void put(String key, Path file, String contentType, String sha256Checksum);

    /** Create only; leave any existing object untouched. */
    void putIfAbsent(String key, Path file, String contentType, String sha256Checksum);

    InputStream openStream(String key);

    boolean delete(String key);

    boolean exists(String key);
}
