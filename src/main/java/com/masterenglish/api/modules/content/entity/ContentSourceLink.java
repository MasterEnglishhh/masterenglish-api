package com.masterenglish.api.modules.content.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "content_source_links")
public class ContentSourceLink {

    @EmbeddedId private ContentSourceLinkId id;

    @Column(name = "usage_note", columnDefinition = "text")
    private String usageNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_snapshot", columnDefinition = "jsonb")
    private String sourceSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public void captureSourceSnapshot(String snapshot) {
        this.sourceSnapshot = snapshot;
    }

    public ContentSourceLink(
            UUID revisionId, UUID sourceId, String usageNote, String sourceSnapshot) {
        this.id = new ContentSourceLinkId(revisionId, sourceId);
        this.usageNote = usageNote;
        this.sourceSnapshot = sourceSnapshot;
        this.createdAt = Instant.now();
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentSourceLinkId implements Serializable {
        @Column(name = "revision_id", nullable = false)
        private UUID revisionId;

        @Column(name = "source_id", nullable = false)
        private UUID sourceId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ContentSourceLinkId that)) return false;
            return Objects.equals(revisionId, that.revisionId)
                    && Objects.equals(sourceId, that.sourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(revisionId, sourceId);
        }
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
