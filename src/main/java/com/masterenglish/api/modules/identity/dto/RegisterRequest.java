package com.masterenglish.api.modules.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 256) String password,
        @NotBlank @Size(max = 30) String firstName,
        @NotBlank @Size(max = 30) String lastName) {
    public RegisterRequest {
        email = email == null ? null : email.strip();
        firstName = firstName == null ? null : firstName.strip();
        lastName = lastName == null ? null : lastName.strip();
    }

    @Override
    public String toString() {
        return "RegisterRequest[redacted]";
    }
}
