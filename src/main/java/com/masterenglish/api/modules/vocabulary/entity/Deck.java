package com.masterenglish.api.modules.vocabulary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "decks")
public class Deck {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Generated
    @Column(name = "content_type", insertable = false, updatable = false)
    private String contentType;

    @Column(name = "published_revision_id", nullable = false)
    private UUID publishedRevisionId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 2)
    private String level;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Deck(UUID id, UUID publishedRevisionId, String title, String description, String level) {
        this.id = id;
        this.publishedRevisionId = publishedRevisionId;
        this.title = title;
        this.description = description;
        this.level = level;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(UUID publishedRevisionId, String title, String description, String level) {
        this.publishedRevisionId = publishedRevisionId;
        this.title = title;
        this.description = description;
        this.level = level;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
