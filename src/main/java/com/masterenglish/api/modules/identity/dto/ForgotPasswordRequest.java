package com.masterenglish.api.modules.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 254) String email) {
    public ForgotPasswordRequest {
        email = email == null ? null : email.strip();
    }
}
