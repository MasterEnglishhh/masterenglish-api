package com.masterenglish.api.modules.content.service;

import java.net.URI;
import java.util.UUID;

public interface PronunciationService {
    String audioUrl(UUID wordId, UUID revisionId);

    String audioUrl(UUID wordId, UUID revisionId, UUID senseId);

    URI playbackLocation(UUID revisionId);

    URI playbackLocation(UUID revisionId, UUID senseId);
}
