package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An option as it was when the attempt started, including its correctness. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_snapshot_options")
public class AttemptSnapshotOption {

    @Id private UUID id;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "question_position", nullable = false, updatable = false)
    private int questionPosition;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "is_correct", nullable = false, updatable = false)
    private boolean correct;

    public AttemptSnapshotOption(
            UUID id,
            UUID attemptId,
            int questionPosition,
            int position,
            String body,
            boolean correct) {
        this.id = id;
        this.attemptId = attemptId;
        this.questionPosition = questionPosition;
        this.position = position;
        this.body = body;
        this.correct = correct;
    }
}
