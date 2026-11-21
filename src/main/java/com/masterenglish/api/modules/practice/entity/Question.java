package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Read model of the published QUESTION revision. Replaced whenever a new revision is published. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "questions")
public class Question {

    @Id private UUID id;

    @Column(name = "published_revision_id", nullable = false)
    private UUID publishedRevisionId;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(name = "question_type_code", nullable = false, length = 32)
    private String questionTypeCode;

    @Column(name = "practice_mode", nullable = false, length = 32)
    private String practiceMode;

    @Column(nullable = false, length = 8)
    private String difficulty;

    @Column(nullable = false)
    private String prompt;

    @Column(nullable = false)
    private String explanation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Question(
            UUID id,
            UUID publishedRevisionId,
            String questionTypeCode,
            String practiceMode,
            String difficulty,
            String prompt,
            String explanation,
            Instant now) {
        this.id = id;
        this.publishedRevisionId = publishedRevisionId;
        this.category = "GRAMMAR";
        this.questionTypeCode = questionTypeCode;
        this.practiceMode = practiceMode;
        this.difficulty = difficulty;
        this.prompt = prompt;
        this.explanation = explanation;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replaceWith(
            UUID revisionId,
            String questionTypeCode,
            String practiceMode,
            String difficulty,
            String prompt,
            String explanation,
            Instant now) {
        this.publishedRevisionId = revisionId;
        this.questionTypeCode = questionTypeCode;
        this.practiceMode = practiceMode;
        this.difficulty = difficulty;
        this.prompt = prompt;
        this.explanation = explanation;
        this.updatedAt = now;
    }
}
