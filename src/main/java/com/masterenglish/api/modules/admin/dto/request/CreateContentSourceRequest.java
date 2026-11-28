package com.masterenglish.api.modules.admin.dto.request;

import com.masterenglish.api.common.validation.HttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateContentSourceRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank
                @Pattern(
                        regexp = "^(SELF_AUTHORED|EXTERNAL)$",
                        message = "sourceType must be SELF_AUTHORED or EXTERNAL")
                String sourceType,
        @HttpUrl(message = "sourceUrl must be an http or https URL") String sourceUrl,
        @Size(max = 255) String author,
        @Size(max = 100) String licenseCode,
        @HttpUrl(message = "licenseUrl must be an http or https URL") String licenseUrl,
        String attribution,
        String permissionNote) {}
