package com.masterenglish.api.modules.grammar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read projection of a published grammar lesson. Written only by the publication listener. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "grammar_lessons")
public class GrammarLesson {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Generated
    @Column(name = "content_type", insertable = false, updatable = false)
    private String contentType;

    @Column(name = "published_revision_id", nullable = false)
    private UUID publishedRevisionId;

    @Column(name = "topic_id", nullable = false)
    private UUID topicId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "body_schema_version", nullable = false)
    private short bodySchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String body;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "estimated_minutes")
    private Short estimatedMinutes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GrammarLesson(
            UUID id,
            UUID publishedRevisionId,
            UUID topicId,
            String title,
            String body,
            int sortOrder,
            Short estimatedMinutes,
            Instant now) {
        this.id = id;
        this.publishedRevisionId = publishedRevisionId;
        this.topicId = topicId;
        this.title = title;
        this.bodySchemaVersion = 1;
        this.body = body;
        this.sortOrder = sortOrder;
        this.estimatedMinutes = estimatedMinutes;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replaces the projection with the snapshot of a newly published revision. */
    public void update(
            UUID publishedRevisionId,
            UUID topicId,
            String title,
            String body,
            int sortOrder,
            Short estimatedMinutes,
            Instant now) {
        this.publishedRevisionId = publishedRevisionId;
        this.topicId = topicId;
        this.title = title;
        this.body = body;
        this.sortOrder = sortOrder;
        this.estimatedMinutes = estimatedMinutes;
        this.updatedAt = now;
    }
}
