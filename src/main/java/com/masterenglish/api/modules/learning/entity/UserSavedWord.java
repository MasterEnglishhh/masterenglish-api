package com.masterenglish.api.modules.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_saved_words")
public class UserSavedWord {

    @EmbeddedId private UserSavedWordId id;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private Instant savedAt;

    public UserSavedWord(UUID userId, UUID wordSenseId) {
        this.id = new UserSavedWordId(userId, wordSenseId);
        this.savedAt = Instant.now();
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSavedWordId implements Serializable {
        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "word_sense_id", nullable = false)
        private UUID wordSenseId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UserSavedWordId that)) return false;
            return Objects.equals(userId, that.userId)
                    && Objects.equals(wordSenseId, that.wordSenseId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, wordSenseId);
        }
    }

    @PrePersist
    void onPersist() {
        if (savedAt == null) {
            savedAt = Instant.now();
        }
    }
}
