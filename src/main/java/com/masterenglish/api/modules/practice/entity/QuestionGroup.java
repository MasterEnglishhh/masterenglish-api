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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "question_groups")
public class QuestionGroup {

    @Id private UUID id;

    @Column(name = "published_revision_id", nullable = false)
    private UUID publishedRevisionId;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(name = "part_number")
    private Integer partNumber;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "group_type", nullable = false, length = 24)
    private String groupType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public QuestionGroup(
            UUID id,
            UUID publishedRevisionId,
            String category,
            Integer partNumber,
            String title,
            String groupType,
            Instant now) {
        this.id = id;
        this.publishedRevisionId = publishedRevisionId;
        this.category = category;
        this.partNumber = partNumber;
        this.title = title;
        this.groupType = groupType;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replaceWith(
            UUID revisionId,
            String category,
            Integer partNumber,
            String title,
            String groupType,
            Instant now) {
        this.publishedRevisionId = revisionId;
        this.category = category;
        this.partNumber = partNumber;
        this.title = title;
        this.groupType = groupType;
        this.updatedAt = now;
    }
}
