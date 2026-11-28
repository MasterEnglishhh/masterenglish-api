package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ImportJobRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportJobRowRepository
        extends JpaRepository<ImportJobRow, ImportJobRow.ImportJobRowId> {

    List<ImportJobRow> findByJobIdOrderByRowNumberAsc(UUID jobId);

    Page<ImportJobRow> findByJobIdOrderByRowNumberAsc(UUID jobId, Pageable pageable);

    Page<ImportJobRow> findByJobIdAndStatusOrderByRowNumberAsc(
            UUID jobId, String status, Pageable pageable);

    Optional<ImportJobRow> findByJobIdAndRowNumber(UUID jobId, int rowNumber);

    long countByJobIdAndStatus(UUID jobId, String status);
}
