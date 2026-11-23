package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The learner's current choice for one question. Only one per question; it can change until submit.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_answers")
public class AttemptAnswer {

    @EmbeddedId private Id id;

    @Column(name = "selected_option_id", nullable = false)
    private UUID selectedOptionId;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    public AttemptAnswer(UUID attemptId, int questionPosition, UUID selectedOptionId, Instant now) {
        this.id = new Id(attemptId, questionPosition);
        this.selectedOptionId = selectedOptionId;
        this.answeredAt = now;
    }

    public void choose(UUID optionId, Instant now) {
        this.selectedOptionId = optionId;
        this.answeredAt = now;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {

        @Column(name = "attempt_id", nullable = false)
        private UUID attemptId;

        @Column(name = "question_position", nullable = false)
        private int questionPosition;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id that)) return false;
            return questionPosition == that.questionPosition
                    && Objects.equals(attemptId, that.attemptId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attemptId, questionPosition);
        }
    }
}
