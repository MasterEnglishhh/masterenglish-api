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

/** A public option of a sealed question. Correctness is never stored here (see answer keys). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_snapshot_options")
public class AttemptSnapshotOption {

    @EmbeddedId private Id id;

    @Column(nullable = false)
    private short position;

    @Column(name = "public_body")
    private String publicBody;

    public AttemptSnapshotOption(
            UUID snapshotId, UUID optionId, short position, String publicBody) {
        this.id = new Id(snapshotId, optionId);
        this.position = position;
        this.publicBody = publicBody;
    }

    @jakarta.persistence.Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "snapshot_id", nullable = false)
        private UUID snapshotId;

        @Column(name = "option_id", nullable = false)
        private UUID optionId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id that)) return false;
            return Objects.equals(snapshotId, that.snapshotId)
                    && Objects.equals(optionId, that.optionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, optionId);
        }
    }
}
