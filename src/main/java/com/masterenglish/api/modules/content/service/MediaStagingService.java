package com.masterenglish.api.modules.content.service;

import com.masterenglish.api.modules.content.dto.response.StageMediaResult;
import java.io.InputStream;
import java.util.UUID;

public interface MediaStagingService {
    StageMediaResult stageMediaUpload(
            UUID actorId, UUID uploadRequestId, InputStream inputStream, String declaredMimeType);
}
