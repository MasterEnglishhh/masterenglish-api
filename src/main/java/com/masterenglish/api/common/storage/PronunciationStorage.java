package com.masterenglish.api.common.storage;

import java.net.URI;
import java.nio.file.Path;

/** Private media storage; credentials and signed URL generation stay on the server. */
public interface PronunciationStorage {
    String bucket();

    void put(String key, Path file, String checksum);

    URI signGet(String key);
}
