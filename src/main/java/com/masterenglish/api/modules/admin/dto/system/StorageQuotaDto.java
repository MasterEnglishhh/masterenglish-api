package com.masterenglish.api.modules.admin.dto.system;

public record StorageQuotaDto(
        String bucket,
        long usedBytes,
        long maxBytes,
        String usedFormatted,
        String limitFormatted,
        double percentUsed,
        long filesCount) {}
