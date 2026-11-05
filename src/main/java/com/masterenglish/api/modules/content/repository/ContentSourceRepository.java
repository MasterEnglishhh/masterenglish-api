package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentSourceRepository extends JpaRepository<ContentSource, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("SELECT s FROM ContentSource s WHERE s.id=:id")
    Optional<ContentSource> findByIdForShare(
            @org.springframework.data.repository.query.Param("id") UUID id);

    Optional<ContentSource> findByTitle(String title);
}
