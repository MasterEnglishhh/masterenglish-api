package com.masterenglish.api.modules.content.service;

import com.masterenglish.api.modules.content.dto.request.ReserveMediaUploadRequest;
import com.masterenglish.api.modules.content.dto.response.ReserveMediaUploadResult;
import java.util.UUID;

public interface MediaReservationService {
    ReserveMediaUploadResult reserveContentUpload(UUID actorId, ReserveMediaUploadRequest request);
}
