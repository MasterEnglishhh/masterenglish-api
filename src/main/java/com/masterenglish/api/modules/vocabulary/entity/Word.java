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
@Table(name = "words")
public class Word {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Generated
    @Column(name = "content_type", insertable = false, updatable = false)
    private String contentType;

    @Column(name = "published_revision_id", nullable = false)
    private UUID publishedRevisionId;

    @Column(nullable = false, length = 150)
    private String lemma;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(length = 150)
    private String ipa;

    @Column(name = "audio_media_id")
    private UUID audioMediaId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Word(
            UUID id,
            UUID publishedRevisionId,
            String lemma,
            String languageCode,
            String ipa,
            UUID audioMediaId) {
        this.id = id;
        this.publishedRevisionId = publishedRevisionId;
        this.lemma = lemma;
        this.languageCode = languageCode != null ? languageCode : "en";
        this.ipa = ipa;
        this.audioMediaId = audioMediaId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(
            UUID publishedRevisionId,
            String lemma,
            String languageCode,
            String ipa,
            UUID audioMediaId) {
        this.publishedRevisionId = publishedRevisionId;
        this.lemma = lemma;
        this.languageCode = languageCode != null ? languageCode : "en";
        this.ipa = ipa;
        this.audioMediaId = audioMediaId;
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
