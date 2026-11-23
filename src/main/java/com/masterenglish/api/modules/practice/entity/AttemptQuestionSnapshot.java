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

/** A question as it was when the attempt started. Later publications never change it. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_question_snapshots")
public class AttemptQuestionSnapshot {

    @EmbeddedId private Id id;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "topic_id", nullable = false, updatable = false)
    private UUID topicId;

    @Column(name = "question_type_code", nullable = false, length = 32, updatable = false)
    private String questionTypeCode;

    @Column(name = "practice_mode", nullable = false, length = 32, updatable = false)
    private String practiceMode;

    @Column(nullable = false, updatable = false)
    private String prompt;

    @Column(nullable = false, updatable = false)
    private String explanation;

    public AttemptQuestionSnapshot(
            UUID attemptId,
            int position,
            UUID questionId,
            UUID topicId,
            String questionTypeCode,
            String practiceMode,
            String prompt,
            String explanation) {
        this.id = new Id(attemptId, position);
        this.questionId = questionId;
        this.topicId = topicId;
        this.questionTypeCode = questionTypeCode;
        this.practiceMode = practiceMode;
        this.prompt = prompt;
        this.explanation = explanation;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {

        @Column(name = "attempt_id", nullable = false)
        private UUID attemptId;

        @Column(name = "position", nullable = false)
        private int position;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id that)) return false;
            return position == that.position && Objects.equals(attemptId, that.attemptId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attemptId, position);
        }
    }
}
