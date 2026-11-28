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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "practice_sets")
public class PracticeSet {

    @Id private UUID id;

    @Column(name = "published_revision_id", nullable = false)
    private UUID publishedRevisionId;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "selection_mode", nullable = false, length = 8)
    private String selectionMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection_config", nullable = false, columnDefinition = "jsonb")
    private String selectionConfig;

    @Column(name = "deck_id")
    private UUID deckId;

    @Column(name = "topic_id")
    private UUID topicId;

    @Column(name = "part_number")
    private Integer partNumber;

    @Column(name = "time_limit_seconds")
    private Integer timeLimitSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PracticeSet(
            UUID id,
            UUID publishedRevisionId,
            String category,
            String title,
            String selectionMode,
            String selectionConfig,
            UUID deckId,
            UUID topicId,
            Integer partNumber,
            Integer timeLimitSeconds,
            Instant now) {
        this.id = id;
        this.publishedRevisionId = publishedRevisionId;
        this.category = category;
        this.title = title;
        this.selectionMode = selectionMode;
        this.selectionConfig = selectionConfig != null ? selectionConfig : "{}";
        this.deckId = deckId;
        this.topicId = topicId;
        this.partNumber = partNumber;
        this.timeLimitSeconds = timeLimitSeconds;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replaceWith(
            UUID revisionId,
            String category,
            String title,
            String selectionMode,
            String selectionConfig,
            UUID deckId,
            UUID topicId,
            Integer partNumber,
            Integer timeLimitSeconds,
            Instant now) {
        this.publishedRevisionId = revisionId;
        this.category = category;
        this.title = title;
        this.selectionMode = selectionMode;
        this.selectionConfig = selectionConfig != null ? selectionConfig : "{}";
        this.deckId = deckId;
        this.topicId = topicId;
        this.partNumber = partNumber;
        this.timeLimitSeconds = timeLimitSeconds;
        this.updatedAt = now;
    }
}
