package com.masterenglish.api.modules.identity.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class UserPrincipal implements UserDetails, AuthenticatedUser {

    private final UUID id;
    private final String email;
    private final String firstName;
    private final String lastName;
    private final String displayName;
    private final String password;
    private final int authVersion;
    private final UserStatus status;
    private final Instant emailVerifiedAt;
    private final Instant lockedUntil;
    private final List<GrantedAuthority> authorities;

    private UserPrincipal(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.firstName = user.getFirstName();
        this.lastName = user.getLastName();
        this.displayName = user.getDisplayName();
        this.password = user.getPasswordHash();
        this.authVersion = user.getAuthVersion();
        this.status = user.getStatus();
        this.emailVerifiedAt = user.getEmailVerifiedAt();
        this.lockedUntil = user.getLockedUntil();
        this.authorities =
                user.getRoleNames().stream()
                        .sorted()
                        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
    }

    public static UserPrincipal create(User user) {
        return new UserPrincipal(user);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getAuthVersion() {
        return authVersion;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @JsonIgnore
    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.LOCKED;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE && emailVerifiedAt != null;
    }
}
