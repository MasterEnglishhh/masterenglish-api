package com.masterenglish.api.modules.practice.entity;

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
@Table(name = "question_options")
public class QuestionOption {

    @Id private UUID id;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String body;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    public QuestionOption(UUID id, UUID questionId, int position, String body, boolean correct) {
        this.id = id;
        this.questionId = questionId;
        this.position = position;
        this.body = body;
        this.correct = correct;
    }
}
