package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentItem;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT i FROM ContentItem i WHERE i.id=:id")
    Optional<ContentItem> findByIdForUpdate(
            @org.springframework.data.repository.query.Param("id") UUID id);

    Optional<ContentItem> findByImportNamespaceAndExternalKey(
            String importNamespace, String externalKey);
}
