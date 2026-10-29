package com.masterenglish.api.modules.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ResendRegistrationOtpRequest(
        @NotNull UUID registrationId, @NotBlank @Email @Size(max = 254) String email) {
    public ResendRegistrationOtpRequest {
        email = email == null ? null : email.strip();
    }
}
