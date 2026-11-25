package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One sealed question of an attempt. {@code questionSnapshot} is public; the answer key and the
 * explanation are private and only leave the server after submission.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_question_snapshots")
public class AttemptQuestionSnapshot {

    @Id private UUID id;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "source_revision_id", nullable = false, updatable = false)
    private UUID sourceRevisionId;

    @Column(nullable = false, length = 16, updatable = false)
    private String category;

    @Column(nullable = false, updatable = false)
    private short ordinal;

    @Column(name = "question_type_code", nullable = false, length = 32, updatable = false)
    private String questionTypeCode;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private short schemaVersion;

    @Column(name = "grading_policy_version", nullable = false, length = 32, updatable = false)
    private String gradingPolicyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question_snapshot", nullable = false, updatable = false)
    private String questionSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_key_snapshot", nullable = false, updatable = false)
    private String answerKeySnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation_snapshot", updatable = false)
    private String explanationSnapshot;

    public AttemptQuestionSnapshot(
            UUID id,
            UUID attemptId,
            UUID questionId,
            UUID sourceRevisionId,
            short ordinal,
            String questionTypeCode,
            String gradingPolicyVersion,
            String questionSnapshot,
            String answerKeySnapshot,
            String explanationSnapshot) {
        this.id = id;
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.sourceRevisionId = sourceRevisionId;
        this.category = Attempt.GRAMMAR;
        this.ordinal = ordinal;
        this.questionTypeCode = questionTypeCode;
        this.schemaVersion = 1;
        this.gradingPolicyVersion = gradingPolicyVersion;
        this.questionSnapshot = questionSnapshot;
        this.answerKeySnapshot = answerKeySnapshot;
        this.explanationSnapshot = explanationSnapshot;
    }
}
