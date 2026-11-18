package com.masterenglish.api.modules.identity.dto.response;

import java.util.Set;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String displayName,
        Set<String> roles,
        String phone,
        UUID avatarMediaId) {}
