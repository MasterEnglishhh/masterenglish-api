package com.masterenglish.api.modules.content.pronunciation;

import java.net.URI;
import java.util.UUID;

public interface PronunciationQuery {
    String audioUrl(UUID wordId, UUID revisionId);

    URI playbackLocation(UUID revisionId);
}
