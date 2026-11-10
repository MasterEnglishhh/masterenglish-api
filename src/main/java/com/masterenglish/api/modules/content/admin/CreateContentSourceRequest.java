package com.masterenglish.api.modules.content.admin;

import jakarta.validation.constraints.NotBlank;

public record CreateContentSourceRequest(
        @NotBlank String title,
        @NotBlank String sourceType,
        String sourceUrl,
        String author,
        String licenseCode,
        String licenseUrl,
        String attribution,
        String permissionNote) {}
