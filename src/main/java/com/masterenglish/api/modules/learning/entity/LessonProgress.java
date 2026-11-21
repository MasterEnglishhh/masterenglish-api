package com.masterenglish.api.modules.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
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
@Table(name = "lesson_progress")
public class LessonProgress {

    @EmbeddedId private LessonProgressId id;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(name = "last_completed_revision_id")
    private UUID lastCompletedRevisionId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false, updatable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LessonProgressId implements Serializable {

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "lesson_id", nullable = false)
        private UUID lessonId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LessonProgressId that)) return false;
            return Objects.equals(userId, that.userId) && Objects.equals(lessonId, that.lessonId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, lessonId);
        }
    }
}
