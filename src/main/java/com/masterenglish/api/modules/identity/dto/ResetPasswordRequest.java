package com.masterenglish.api.modules.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String resetToken,
        @NotBlank @Size(max = 256) String newPassword) {
    public ResetPasswordRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "ResetPasswordRequest[redacted]";
    }
}
