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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "question_topics")
public class QuestionTopic {

    @EmbeddedId private Id id;

    public QuestionTopic(UUID questionId, UUID topicId) {
        this.id = new Id(questionId, topicId);
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {

        @Column(name = "question_id", nullable = false)
        private UUID questionId;

        @Column(name = "topic_id", nullable = false)
        private UUID topicId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id that)) return false;
            return Objects.equals(questionId, that.questionId)
                    && Objects.equals(topicId, that.topicId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(questionId, topicId);
        }
    }
}
