package com.masterenglish.api.modules.content.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.ContentEvents;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface ImportJobService {

    ContentEvents.CreateImportJobResult createWordCsvImportJob(
            UUID actorId,
            UUID requestId,
            String namespace,
            String originalFilename,
            byte[] fileBytes);

    ContentEvents.CreateImportJobResult createQuestionBankJsonImportJob(
            UUID actorId,
            UUID requestId,
            String namespace,
            String originalFilename,
            byte[] fileBytes);

    ContentEvents.ImportJobView getJob(UUID actorId, UUID jobId);

    PageResponse<ContentEvents.ImportJobRowView> getJobRows(
            UUID actorId, UUID jobId, String status, Pageable pageable);

    ContentEvents.ConfirmImportJobResult confirmJob(UUID actorId, UUID jobId);
}
