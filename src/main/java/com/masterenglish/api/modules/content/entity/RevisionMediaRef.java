package com.masterenglish.api.modules.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "revision_media_refs")
@IdClass(RevisionMediaRef.RevisionMediaRefId.class)
public class RevisionMediaRef {

    @Id
    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Id
    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Id
    @Column(name = "usage_role", nullable = false, length = 20)
    private String usageRole;

    public RevisionMediaRef(UUID revisionId, UUID mediaId, String usageRole) {
        this.revisionId = revisionId;
        this.mediaId = mediaId;
        this.usageRole = usageRole != null ? usageRole : "STIMULUS";
    }

    public static class RevisionMediaRefId implements Serializable {
        private UUID revisionId;
        private UUID mediaId;
        private String usageRole;

        public RevisionMediaRefId() {}

        public RevisionMediaRefId(UUID revisionId, UUID mediaId, String usageRole) {
            this.revisionId = revisionId;
            this.mediaId = mediaId;
            this.usageRole = usageRole;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RevisionMediaRefId that = (RevisionMediaRefId) o;
            return Objects.equals(revisionId, that.revisionId)
                    && Objects.equals(mediaId, that.mediaId)
                    && Objects.equals(usageRole, that.usageRole);
        }

        @Override
        public int hashCode() {
            return Objects.hash(revisionId, mediaId, usageRole);
        }
    }
}
