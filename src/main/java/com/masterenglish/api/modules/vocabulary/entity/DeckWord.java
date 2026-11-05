package com.masterenglish.api.modules.vocabulary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "deck_words")
public class DeckWord {

    @EmbeddedId private DeckWordId id;

    @Column(nullable = false)
    private int position;

    public DeckWord(UUID deckId, UUID wordSenseId, int position) {
        this.id = new DeckWordId(deckId, wordSenseId);
        this.position = position;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeckWordId implements Serializable {
        @Column(name = "deck_id", nullable = false)
        private UUID deckId;

        @Column(name = "word_sense_id", nullable = false)
        private UUID wordSenseId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DeckWordId that)) return false;
            return Objects.equals(deckId, that.deckId)
                    && Objects.equals(wordSenseId, that.wordSenseId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deckId, wordSenseId);
        }
    }
}
