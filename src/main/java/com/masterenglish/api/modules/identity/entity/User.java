package com.masterenglish.api.modules.identity.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.masterenglish.api.common.entity.BaseEntity;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.security.RoleCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(nullable = false, length = 254)
    private String email;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 30)
    private String phone;

    @Column(name = "avatar_media_id")
    private UUID avatarMediaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "auth_version", nullable = false)
    private int authVersion;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "user", cascade = {CascadeType.PERSIST, CascadeType.MERGE},
            fetch = FetchType.LAZY, orphanRemoval = true)
    private Set<UserRole> userRoles = new HashSet<>();

    public User(String email, String passwordHash, String displayName) {
        this.email = Objects.requireNonNull(email).strip().toLowerCase(Locale.ROOT);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.displayName = Objects.requireNonNull(displayName).strip();
    }

    public Set<UserRole> getUserRoles() {
        return Collections.unmodifiableSet(userRoles);
    }

    public void updateProfile(String displayName, String phone, UUID avatarMediaId) {
        this.displayName = Objects.requireNonNull(displayName).strip();
        this.phone = phone == null || phone.isBlank() ? null : phone.strip();
        this.avatarMediaId = avatarMediaId;
    }

    public Set<String> getRoleNames() {
        return userRoles.stream().map(link -> link.getRole().getCode().name())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void addRole(Role role) {
        Objects.requireNonNull(role);
        if (userRoles.stream().noneMatch(link -> link.getRole().getCode() == role.getCode())) {
            userRoles.add(new UserRole(this, role));
            if (getId() != null) {
                invalidateAuthentication();
            }
        }
    }

    public void removeRole(RoleCode role) {
        boolean assigned = userRoles.stream().anyMatch(link -> link.getRole().getCode() == role);
        if (!assigned) {
            return;
        }
        if (userRoles.size() == 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A user must retain at least one role.");
        }
        userRoles.removeIf(link -> link.getRole().getCode() == role);
        invalidateAuthentication();
    }

    public void changeStatus(UserStatus status) {
        UserStatus next = Objects.requireNonNull(status);
        if (this.status != next) {
            this.status = next;
            invalidateAuthentication();
        }
    }

    public void changePassword(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new IllegalArgumentException("An encoded password is required.");
        }
        passwordHash = encodedPassword;
        passwordChangedAt = Instant.now();
        invalidateAuthentication();
    }

    private void invalidateAuthentication() {
        authVersion = Math.incrementExact(authVersion);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
