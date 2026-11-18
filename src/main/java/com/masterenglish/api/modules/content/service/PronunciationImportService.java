package com.masterenglish.api.modules.content.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public interface PronunciationImportService {
    int importManifest(Path manifest, UUID actor) throws IOException;

    int importPortableManifest(Path manifest, UUID actor, Map<UUID, UUID> targetRevisions)
            throws IOException;

    Map<UUID, String> validateManifest(Path manifest) throws IOException;
}
