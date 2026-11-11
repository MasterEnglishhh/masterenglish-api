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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "word_senses")
public class WordSense {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "word_id", nullable = false)
    private UUID wordId;

    @Column(name = "word_revision_id", nullable = false)
    private UUID wordRevisionId;

    @Column(name = "sense_no", nullable = false)
    private short senseNo;

    @Column(name = "part_of_speech", nullable = false, length = 24)
    private String partOfSpeech;

    @Column(name = "cefr_level", nullable = false, length = 2)
    private String cefrLevel;

    @Column(name = "meaning_vi", nullable = false, columnDefinition = "text")
    private String meaningVi;

    @Column(name = "definition_en", columnDefinition = "text")
    private String definitionEn;

    @Column(name = "audio_media_id")
    private UUID audioMediaId;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WordSense(
            UUID id,
            UUID wordId,
            UUID wordRevisionId,
            short senseNo,
            String partOfSpeech,
            String cefrLevel,
            String meaningVi,
            String definitionEn,
            UUID audioMediaId) {
        this.id = id;
        this.wordId = wordId;
        this.wordRevisionId = wordRevisionId;
        this.senseNo = senseNo;
        this.partOfSpeech = partOfSpeech;
        this.cefrLevel = cefrLevel;
        this.meaningVi = meaningVi;
        this.definitionEn = definitionEn;
        this.audioMediaId = audioMediaId;
        this.archived = false;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(
            UUID wordRevisionId,
            short senseNo,
            String partOfSpeech,
            String cefrLevel,
            String meaningVi,
            String definitionEn,
            UUID audioMediaId) {
        this.wordRevisionId = wordRevisionId;
        this.archived = false;
        this.senseNo = senseNo;
        this.partOfSpeech = partOfSpeech;
        this.cefrLevel = cefrLevel;
        this.meaningVi = meaningVi;
        this.definitionEn = definitionEn;
        this.audioMediaId = audioMediaId;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.archived = true;
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
