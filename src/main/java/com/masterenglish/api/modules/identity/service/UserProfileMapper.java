package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.entity.User;
import java.util.Objects;

/** Mapping belongs to the application layer; response DTOs contain only response data. */
public final class UserProfileMapper {
    private UserProfileMapper() {}

    public static UserProfileResponse from(User user) {
        Objects.requireNonNull(user, "user must not be null");
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getDisplayName(),
                user.getRoleNames(),
                user.getPhone(),
                user.getAvatarMediaId());
    }
}
