package com.masterenglish.api.modules.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PasswordResetOtpRequest(
        @NotNull UUID challengeId,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String otp) {
    public PasswordResetOtpRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "PasswordResetOtpRequest[redacted]";
    }
}
