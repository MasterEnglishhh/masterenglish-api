package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentSource;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContentSourceRepository extends JpaRepository<ContentSource, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT s FROM ContentSource s WHERE s.id=:id")
    Optional<ContentSource> findByIdForShare(@Param("id") UUID id);

    Optional<ContentSource> findByTitle(String title);
}
