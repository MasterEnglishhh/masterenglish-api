package com.masterenglish.api.modules.content.entity;

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
@Table(name = "revision_dependencies")
public class RevisionDependency {

    @EmbeddedId private RevisionDependencyId id;

    @Column(name = "child_revision_id", nullable = false)
    private UUID childRevisionId;

    public RevisionDependency(UUID parentRevisionId, String relationKey, UUID childRevisionId) {
        this.id = new RevisionDependencyId(parentRevisionId, relationKey);
        this.childRevisionId = childRevisionId;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevisionDependencyId implements Serializable {
        @Column(name = "parent_revision_id", nullable = false)
        private UUID parentRevisionId;

        @Column(name = "relation_key", nullable = false, length = 200)
        private String relationKey;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RevisionDependencyId that)) return false;
            return Objects.equals(parentRevisionId, that.parentRevisionId)
                    && Objects.equals(relationKey, that.relationKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parentRevisionId, relationKey);
        }
    }
}
