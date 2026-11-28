package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
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

/** A topic a mixed attempt was drawn from, pinned to the topic revision published at start. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_source_topics")
public class AttemptSourceTopic {

    @EmbeddedId private Id id;

    @Column(name = "source_revision_id", nullable = false, updatable = false)
    private UUID sourceRevisionId;

    public AttemptSourceTopic(UUID attemptId, UUID topicId, UUID sourceRevisionId) {
        this.id = new Id(attemptId, topicId);
        this.sourceRevisionId = sourceRevisionId;
    }

    @jakarta.persistence.Embeddable
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
