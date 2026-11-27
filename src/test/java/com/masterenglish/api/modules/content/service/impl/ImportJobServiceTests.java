package com.masterenglish.api.modules.content.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.modules.content.entity.ImportJob;
import com.masterenglish.api.modules.content.entity.ImportJobRow;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.ImportJobRepository;
import com.masterenglish.api.modules.content.repository.ImportJobRowRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class ImportJobServiceTests {

    @Test
    void rowErrorsDoNotExposeStorageOrSqlMessage() {
        ImportJobRepository jobRepo = mock(ImportJobRepository.class);
        ImportJobRowRepository rowRepo = mock(ImportJobRowRepository.class);
        ContentSourceRepository sourceRepo = mock(ContentSourceRepository.class);
        ImportJobTxService txService = mock(ImportJobTxService.class);
        IdentityEvents identity = mock(IdentityEvents.class);
        MediaStorage storage = mock(MediaStorage.class);
        ObjectMapper mapper = new ObjectMapper();

        ImportJobServiceImpl service =
                new ImportJobServiceImpl(
                        jobRepo,
                        rowRepo,
                        sourceRepo,
                        txService,
                        new WordCsvParser(mapper),
                        new QuestionBankJsonParser(mapper),
                        identity,
                        mapper,
                        storage);

        UUID actorId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();

        ImportJob job =
                new ImportJob(
                        jobId,
                        actorId,
                        UUID.randomUUID(),
                        "hash",
                        "VOCABULARY_CSV",
                        "ns",
                        "test.csv",
                        "temp",
                        "imports/" + jobId + "/input",
                        100L,
                        "a".repeat(64),
                        Instant.now().plusSeconds(600));
        job.startProcessing(Instant.now(), claimToken, Instant.now().plusSeconds(300));

        ImportJobRow row =
                new ImportJobRow(jobId, 1, "rowhash", "w1", "{\"lemma\":\"apple\"}", "VALID", null);

        when(txService.claimForConfirm(jobId, actorId)).thenReturn(job);
        when(rowRepo.findByJobIdOrderByRowNumberAsc(jobId)).thenReturn(List.of(row));
        when(txService.processWordRow(any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("SQL_SENTINEL_SECRET_LEAK"));

        ImportJob finalized =
                new ImportJob(
                        jobId,
                        actorId,
                        job.getRequestId(),
                        "hash",
                        "VOCABULARY_CSV",
                        "ns",
                        "test.csv",
                        "temp",
                        "imports/" + jobId + "/input",
                        100L,
                        "a".repeat(64),
                        Instant.now().plusSeconds(600));
        finalized.finishProcessing(0, 1, 0, Instant.now());
        when(txService.finalizeJob(eq(jobId), eq(claimToken), anyInt(), anyInt(), anyInt()))
                .thenReturn(finalized);

        service.confirmJob(actorId, jobId);

        ArgumentCaptor<String> errorsCaptor = ArgumentCaptor.forClass(String.class);
        verify(txService).markRowFailed(eq(jobId), eq(claimToken), eq(1), errorsCaptor.capture());
        assertThat(errorsCaptor.getValue())
                .contains("Row processing failed due to an internal error.")
                .doesNotContain("SQL_SENTINEL_SECRET_LEAK");
    }
}
