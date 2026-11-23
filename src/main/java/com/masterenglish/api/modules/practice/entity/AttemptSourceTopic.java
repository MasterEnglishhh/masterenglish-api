package com.masterenglish.api.modules.practice.entity;

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

/** Topics a mixed attempt was started from. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_source_topics")
public class AttemptSourceTopic {

    @EmbeddedId private Id id;

    public AttemptSourceTopic(UUID attemptId, UUID topicId) {
        this.id = new Id(attemptId, topicId);
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {

        @Column(name = "attempt_id", nullable = false)
        private UUID attemptId;

        @Column(name = "topic_id", nullable = false)
        private UUID topicId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id that)) return false;
            return Objects.equals(attemptId, that.attemptId)
                    && Objects.equals(topicId, that.topicId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attemptId, topicId);
        }
    }
}
