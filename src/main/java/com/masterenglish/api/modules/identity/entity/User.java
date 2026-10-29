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
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(nullable = false, length = 254)
    private String email;

    @JsonIgnore
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "first_name", length = 30)
    private String firstName;

    @Column(name = "last_name", length = 30)
    private String lastName;

    @Column(length = 30)
    private String phone;

    @Column(name = "avatar_media_id")
    private UUID avatarMediaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "auth_version", nullable = false)
    private int authVersion;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Getter(AccessLevel.NONE)
    @OneToMany(
            mappedBy = "user",
            cascade = {CascadeType.PERSIST, CascadeType.MERGE},
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    private Set<UserRole> userRoles = new HashSet<>();

    public User(String email, String passwordHash, String firstName, String lastName) {
        this.email = Objects.requireNonNull(email).strip().toLowerCase(Locale.ROOT);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.firstName = normalizeName(firstName);
        this.lastName = normalizeName(lastName);
    }

    public User(String email, String passwordHash, String displayName) {
        this.email = Objects.requireNonNull(email).strip().toLowerCase(Locale.ROOT);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        Objects.requireNonNull(displayName);
        String stripped = displayName.strip();
        int spaceIndex = stripped.lastIndexOf(' ');
        if (spaceIndex > 0) {
            this.lastName = normalizeName(stripped.substring(0, spaceIndex));
            this.firstName = normalizeName(stripped.substring(spaceIndex + 1));
        } else {
            this.firstName = normalizeName(stripped);
            this.lastName = normalizeName(stripped);
        }
    }

    public String getDisplayName() {
        if (lastName != null && !lastName.isBlank() && firstName != null && !firstName.isBlank()) {
            return lastName.strip() + " " + firstName.strip();
        }
        if (lastName != null && !lastName.isBlank()) {
            return lastName.strip();
        }
        if (firstName != null && !firstName.isBlank()) {
            return firstName.strip();
        }
        return "";
    }

    public Set<UserRole> getUserRoles() {
        return Collections.unmodifiableSet(userRoles);
    }

    public void updateProfile(String firstName, String lastName, String phone, UUID avatarMediaId) {
        this.firstName = normalizeName(firstName);
        this.lastName = normalizeName(lastName);
        this.phone = phone == null || phone.isBlank() ? null : phone.strip();
        this.avatarMediaId = avatarMediaId;
    }

    public void updateProfile(String displayName, String phone, UUID avatarMediaId) {
        Objects.requireNonNull(displayName);
        String stripped = displayName.strip();
        int spaceIndex = stripped.lastIndexOf(' ');
        if (spaceIndex > 0) {
            this.lastName = normalizeName(stripped.substring(0, spaceIndex));
            this.firstName = normalizeName(stripped.substring(spaceIndex + 1));
        } else {
            this.firstName = normalizeName(stripped);
            this.lastName = normalizeName(stripped);
        }
        this.phone = phone == null || phone.isBlank() ? null : phone.strip();
        this.avatarMediaId = avatarMediaId;
    }

    public Set<String> getRoleNames() {
        return userRoles.stream()
                .map(link -> link.getRole().getCode().name())
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
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "A user must retain at least one role.");
        }
        userRoles.removeIf(link -> link.getRole().getCode() == role);
        invalidateAuthentication();
    }

    public void markEmailVerified(Instant verifiedAt) {
        this.emailVerifiedAt = Objects.requireNonNull(verifiedAt);
        this.status = UserStatus.ACTIVE;
    }

    public void lock(Instant until) {
        this.status = UserStatus.LOCKED;
        this.lockedUntil = Objects.requireNonNull(until);
        invalidateAuthentication();
    }

    public void unlock() {
        this.status = UserStatus.ACTIVE;
        this.lockedUntil = null;
        invalidateAuthentication();
    }

    public void changeStatus(UserStatus status) {
        UserStatus next = Objects.requireNonNull(status);
        if (next == UserStatus.LOCKED) {
            throw new IllegalArgumentException("Use lock(Instant until) to lock an account.");
        }
        if (this.status != next) {
            this.status = next;
            if (next == UserStatus.PENDING_VERIFICATION) {
                this.emailVerifiedAt = null;
                this.lockedUntil = null;
            } else if (next == UserStatus.ACTIVE) {
                if (this.emailVerifiedAt == null) {
                    this.emailVerifiedAt = Instant.now();
                }
                this.lockedUntil = null;
            } else if (next == UserStatus.DISABLED) {
                this.lockedUntil = null;
            }
            invalidateAuthentication();
        }
    }

    private static String normalizeName(String str) {
        return (str != null && !str.isBlank()) ? str.strip() : null;
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
        return status == UserStatus.ACTIVE && emailVerifiedAt != null;
    }
}
