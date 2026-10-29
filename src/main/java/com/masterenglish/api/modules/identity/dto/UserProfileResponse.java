package com.masterenglish.api.modules.identity.dto;

import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record UserProfileResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String displayName,
        Set<String> roles,
        String phone,
        UUID avatarMediaId) {

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

    public static UserProfileResponse from(UserPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        Set<String> roles =
                principal.getAuthorities().stream()
                        .map(
                                a ->
                                        a.getAuthority().startsWith("ROLE_")
                                                ? a.getAuthority().substring(5)
                                                : a.getAuthority())
                        .collect(Collectors.toUnmodifiableSet());
        return new UserProfileResponse(
                principal.getId(),
                principal.getEmail(),
                principal.getFirstName(),
                principal.getLastName(),
                principal.getDisplayName(),
                roles,
                null,
                null);
    }
}
