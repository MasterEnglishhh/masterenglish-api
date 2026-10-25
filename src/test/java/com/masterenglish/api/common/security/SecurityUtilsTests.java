package com.masterenglish.api.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityUtilsTests {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doesNotGuessAnEmailOrUserIdFromAnUnrelatedPrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "someone@example.com", "", List.of()));
        assertThat(SecurityUtils.getCurrentUserEmail()).isEmpty();
        assertThat(SecurityUtils.getCurrentUserId()).isEmpty();
    }

    @Test
    void checksMultipleAuthoritiesWithoutImplyingAdminInheritance() {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                new Principal(id, "learner@example.com"),
                                "",
                                List.of(
                                        new SimpleGrantedAuthority("ROLE_LEARNER"),
                                        new SimpleGrantedAuthority("ROLE_CONTENT_ADMIN"))));
        assertThat(SecurityUtils.getCurrentUserId()).contains(id);
        assertThat(SecurityUtils.hasRole("LEARNER")).isTrue();
        assertThat(SecurityUtils.hasRole("ROLE_CONTENT_ADMIN")).isTrue();
        assertThat(SecurityUtils.hasRole("SYSTEM_ADMIN")).isFalse();
    }

    private record Principal(UUID id, String email) implements AuthenticatedUser {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getEmail() {
            return email;
        }
    }
}
