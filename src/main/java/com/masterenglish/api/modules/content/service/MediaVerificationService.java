package com.masterenglish.api.modules.content.service;

import com.masterenglish.api.modules.content.dto.response.CompleteMediaResult;
import java.util.UUID;

public interface MediaVerificationService {

    CompleteMediaResult completeUpload(UUID actorId, UUID uploadRequestId);

    CompleteMediaResult verifyAndFinalize(UUID uploadRequestId, UUID claimToken);

    CompleteMediaResult completeAndVerify(UUID actorId, UUID uploadRequestId);
}
