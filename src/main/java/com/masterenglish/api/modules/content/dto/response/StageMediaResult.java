package com.masterenglish.api.modules.content.dto.response;

import java.util.UUID;

public record StageMediaResult(
        UUID uploadRequestId, UUID mediaId, long sizeBytes, String sha256Checksum, String status) {}
