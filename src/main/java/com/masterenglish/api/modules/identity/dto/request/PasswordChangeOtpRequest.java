package com.masterenglish.api.modules.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record PasswordChangeOtpRequest(
        @NotNull UUID challengeId, @NotBlank @Pattern(regexp = "[0-9]{6}") String otp) {
    @Override
    public String toString() {
        return "PasswordChangeOtpRequest[redacted]";
    }
}
