package com.masterenglish.api.modules.vocabulary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "word_sense_examples")
public class WordSenseExample {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "word_sense_id", nullable = false)
    private UUID wordSenseId;

    @Column(nullable = false)
    private short position;

    @Column(name = "example_en", nullable = false, columnDefinition = "text")
    private String exampleEn;

    @Column(name = "example_vi", columnDefinition = "text")
    private String exampleVi;

    public WordSenseExample(
            UUID id, UUID wordSenseId, short position, String exampleEn, String exampleVi) {
        this.id = id != null ? id : UUID.randomUUID();
        this.wordSenseId = wordSenseId;
        this.position = position;
        this.exampleEn = exampleEn;
        this.exampleVi = exampleVi;
    }
}
