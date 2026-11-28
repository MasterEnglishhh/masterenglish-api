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

    @Column(name = "part_number")
    private Integer partNumber;

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

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "group_position")
    private Integer groupPosition;

    @Column(name = "in_current_group", nullable = false)
    private boolean inCurrentGroup = false;

    @Column(name = "blank_key", length = 80)
    private String blankKey;

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
        this(
                id,
                publishedRevisionId,
                "GRAMMAR",
                null,
                questionTypeCode,
                practiceMode,
                difficulty,
                prompt,
                explanation,
                now);
    }

    public Question(
            UUID id,
            UUID publishedRevisionId,
            String category,
            Integer partNumber,
            String questionTypeCode,
            String practiceMode,
            String difficulty,
            String prompt,
            String explanation,
            Instant now) {
        this.id = id;
        this.publishedRevisionId = publishedRevisionId;
        this.category = category != null ? category : "GRAMMAR";
        this.partNumber = partNumber;
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
        replaceWith(
                revisionId,
                this.category,
                this.partNumber,
                questionTypeCode,
                practiceMode,
                difficulty,
                prompt,
                explanation,
                now);
    }

    public void replaceWith(
            UUID revisionId,
            String category,
            Integer partNumber,
            String questionTypeCode,
            String practiceMode,
            String difficulty,
            String prompt,
            String explanation,
            Instant now) {
        this.publishedRevisionId = revisionId;
        this.category = category != null ? category : this.category;
        this.partNumber = partNumber;
        this.questionTypeCode = questionTypeCode;
        this.practiceMode = practiceMode;
        this.difficulty = difficulty;
        this.prompt = prompt;
        this.explanation = explanation;
        this.updatedAt = now;
    }

    public void assignGroup(UUID groupId, int position, String blankKey) {
        if (this.inCurrentGroup && this.groupId != null && !this.groupId.equals(groupId)) {
            throw new com.masterenglish.api.common.error.BusinessException(
                    com.masterenglish.api.common.error.ErrorCode.CONFLICT,
                    "Question already belongs to another group.");
        }
        this.groupId = groupId;
        this.groupPosition = position;
        this.inCurrentGroup = true;
        this.blankKey = blankKey;
    }

    public void retireFromGroup() {
        this.inCurrentGroup = false;
    }

    public boolean isGrouped() {
        return groupId != null && inCurrentGroup;
    }
}
