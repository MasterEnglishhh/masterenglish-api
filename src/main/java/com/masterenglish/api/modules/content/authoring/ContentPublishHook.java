package com.masterenglish.api.modules.content.authoring;

import java.util.UUID;

public interface ContentPublishHook {
    boolean supports(String contentType);

    void onPublish(UUID itemId, UUID revisionId, String contentType, String snapshotJson);
}
