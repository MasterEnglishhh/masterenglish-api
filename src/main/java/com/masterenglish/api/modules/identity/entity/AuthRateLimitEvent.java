package com.masterenglish.api.modules.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "auth_rate_limit_events")
public class AuthRateLimitEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_limit_id", nullable = false)
    private AuthRateLimit rateLimit;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public AuthRateLimitEvent(AuthRateLimit rateLimit) {
        this.rateLimit = Objects.requireNonNull(rateLimit);
        this.occurredAt = Instant.now();
    }

    public AuthRateLimitEvent(AuthRateLimit rateLimit, Instant occurredAt) {
        this.rateLimit = Objects.requireNonNull(rateLimit);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
