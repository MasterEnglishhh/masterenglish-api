package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A mistake pinned to a practice-from-mistakes attempt. The user id keeps ownership in the DB. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_source_mistakes")
public class AttemptSourceMistake {

    @EmbeddedId private Id id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    public AttemptSourceMistake(UUID attemptId, UUID userId, UUID mistakeId) {
        this.id = new Id(attemptId, mistakeId);
        this.userId = userId;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "attempt_id", nullable = false)
        private UUID attemptId;

        @Column(name = "mistake_id", nullable = false)
        private UUID mistakeId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id that)) return false;
            return Objects.equals(attemptId, that.attemptId)
                    && Objects.equals(mistakeId, that.mistakeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attemptId, mistakeId);
        }
    }
}
