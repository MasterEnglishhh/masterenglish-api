package com.masterenglish.api.common.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<Authentication> getAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                        && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken)
                ? Optional.of(authentication)
                : Optional.empty();
    }

    public static Optional<UUID> getCurrentUserId() {
        return getPrincipal().map(AuthenticatedUser::getId);
    }

    public static Optional<String> getCurrentUserEmail() {
        return getPrincipal().map(AuthenticatedUser::getEmail);
    }

    public static boolean hasRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        String expected = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
        return getAuthentication()
                .map(
                        authentication ->
                                authentication.getAuthorities().stream()
                                        .anyMatch(
                                                authority ->
                                                        expected.equals(authority.getAuthority())))
                .orElse(false);
    }

    private static Optional<AuthenticatedUser> getPrincipal() {
        return getAuthentication()
                .map(Authentication::getPrincipal)
                .filter(AuthenticatedUser.class::isInstance)
                .map(AuthenticatedUser.class::cast);
    }
}
