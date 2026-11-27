package com.masterenglish.api.modules.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Rules are seeded by Flyway and managed by SYSTEM_ADMIN via M10.10. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "reminder_rules")
public class ReminderRule {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "minimum_gap_minutes", nullable = false)
    private int minimumGapMinutes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public void update(boolean enabled, int minimumGapMinutes) {
        if (minimumGapMinutes < 0) {
            throw new IllegalArgumentException("minimumGapMinutes must be >= 0");
        }
        this.enabled = enabled;
        this.minimumGapMinutes = minimumGapMinutes;
    }
}
