package com.masterenglish.api.modules.content.service;

import java.net.URI;
import java.util.UUID;

public interface PronunciationService {
    String audioUrl(UUID wordId, UUID revisionId);

    URI playbackLocation(UUID revisionId);
}
