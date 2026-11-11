package com.masterenglish.api.modules.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank @Size(max = 256) String currentPassword,
        @NotBlank @Size(max = 256) String newPassword,
        @NotBlank @Size(max = 256) String confirmPassword) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
