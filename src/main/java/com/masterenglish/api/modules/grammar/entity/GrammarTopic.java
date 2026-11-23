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

/** Read projection of a published grammar topic. Written only by the publication listener. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "grammar_topics")
public class GrammarTopic {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Generated
    @Column(name = "content_type", insertable = false, updatable = false)
    private String contentType;

    @Column(name = "published_revision_id", nullable = false)
    private UUID publishedRevisionId;

    @Column(nullable = false, length = 120, unique = true)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 16)
    private String difficulty;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GrammarTopic(
            UUID id,
            UUID publishedRevisionId,
            String slug,
            String name,
            String description,
            String difficulty,
            int sortOrder,
            Instant now) {
        this.id = id;
        this.publishedRevisionId = publishedRevisionId;
        this.slug = slug;
        this.name = name;
        this.description = description;
        this.difficulty = difficulty;
        this.sortOrder = sortOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replaces the projection with the snapshot of a newly published revision. */
    public void update(
            UUID publishedRevisionId,
            String slug,
            String name,
            String description,
            String difficulty,
            int sortOrder,
            Instant now) {
        this.publishedRevisionId = publishedRevisionId;
        this.slug = slug;
        this.name = name;
        this.description = description;
        this.difficulty = difficulty;
        this.sortOrder = sortOrder;
        this.updatedAt = now;
    }
}
