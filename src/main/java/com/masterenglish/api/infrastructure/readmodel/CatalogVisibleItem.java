package com.masterenglish.api.infrastructure.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Shared visibility view used by paginated catalog queries. */
@Entity
@Immutable
@Table(name = "catalog_visible_items")
class CatalogVisibleItem {
    @Id private UUID id;

    /** The revision currently visible; projections match it to stay in the catalog. */
    @Column(name = "published_revision_id", nullable = false)
    private UUID publishedRevisionId;

    protected CatalogVisibleItem() {}
}
