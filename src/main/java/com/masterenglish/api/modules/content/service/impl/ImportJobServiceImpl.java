package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.ImportJob;
import com.masterenglish.api.modules.content.entity.ImportJobRow;
import com.masterenglish.api.modules.content.entity.StorageAllocation;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.ImportJobRepository;
import com.masterenglish.api.modules.content.repository.ImportJobRowRepository;
import com.masterenglish.api.modules.content.service.ImportJobService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ImportJobServiceImpl implements ImportJobService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MiB
    private static final UUID TEMP_POOL_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000002");

    private final ImportJobRepository jobRepository;
    private final ImportJobRowRepository rowRepository;
    private final ContentSourceRepository sourceRepository;
    private final ImportJobTxService txService;
    private final WordCsvParser csvParser;
    private final QuestionBankJsonParser jsonParser;
    private final IdentityEvents identity;
    private final ObjectMapper mapper;
    private final MediaStorage mediaStorage;

    public ImportJobServiceImpl(
            ImportJobRepository jobRepository,
            ImportJobRowRepository rowRepository,
            ContentSourceRepository sourceRepository,
            ImportJobTxService txService,
            WordCsvParser csvParser,
            QuestionBankJsonParser jsonParser,
            IdentityEvents identity,
            ObjectMapper mapper,
            MediaStorage mediaStorage) {
        this.jobRepository = jobRepository;
        this.rowRepository = rowRepository;
        this.sourceRepository = sourceRepository;
        this.txService = txService;
        this.csvParser = csvParser;
        this.jsonParser = jsonParser;
        this.identity = identity;
        this.mapper = mapper;
        this.mediaStorage = mediaStorage;
    }

    @Override
    public ContentEvents.CreateImportJobResult createWordCsvImportJob(
            UUID actorId,
            UUID requestId,
            String namespace,
            String originalFilename,
            byte[] fileBytes) {
        identity.verifyContentAdmin(actorId);

        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "File must not be empty.");
        }
        if (fileBytes.length > MAX_FILE_SIZE) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "File size exceeds 5 MiB maximum limit.");
        }

        String fileSha256 = sha256Hex(fileBytes);
        String requestHash = sha256Hex("VOCABULARY_CSV:" + namespace + ":" + fileSha256);

        Optional<ImportJob> existing =
                jobRepository.findByCreatedByAndRequestId(actorId, requestId);
        if (existing.isPresent()) {
            ImportJob existingJob = existing.get();
            if (existingJob.getRequestHash().equals(requestHash)) {
                log.info(
                        "Replaying existing import job {} for request {}",
                        existingJob.getId(),
                        requestId);
                return replay(existingJob, fileBytes);
            } else {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Request ID already used with different file or parameters.");
            }
        }

        // Parse CSV outside of DB transaction
        Set<UUID> existingSources =
                new HashSet<>(
                        sourceRepository.findAll().stream()
                                .filter(ContentSource::availableForPublication)
                                .map(ContentSource::getId)
                                .toList());

        WordCsvParser.ParseResult parseResult = csvParser.parse(fileBytes, existingSources);

        UUID jobId = UUID.randomUUID();
        String objectKey = "imports/" + jobId + "/input";

        ImportJob job =
                new ImportJob(
                        jobId,
                        actorId,
                        requestId,
                        requestHash,
                        "VOCABULARY_CSV",
                        namespace != null ? namespace : "default",
                        originalFilename != null ? originalFilename : "import.csv",
                        mediaStorage.bucket(),
                        objectKey,
                        (long) fileBytes.length,
                        fileSha256,
                        Instant.now().plusSeconds(600));

        List<ImportJobRow> rows = new ArrayList<>(parseResult.rows().size());
        for (WordCsvParser.ParsedRow pr : parseResult.rows()) {
            rows.add(
                    new ImportJobRow(
                            jobId,
                            pr.rowNumber(),
                            pr.inputHash(),
                            pr.externalKey(),
                            pr.payloadJson(),
                            pr.status(),
                            pr.errorsJson()));
        }

        StorageAllocation allocation =
                StorageAllocation.forImportJob(
                        UUID.randomUUID(),
                        TEMP_POOL_ID,
                        "import:" + jobId,
                        jobId,
                        (long) fileBytes.length,
                        "RESERVED",
                        mediaStorage.bucket(),
                        objectKey);

        return storeInput(job, rows, allocation, fileBytes);
    }

    @Override
    public ContentEvents.CreateImportJobResult createQuestionBankJsonImportJob(
            UUID actorId,
            UUID requestId,
            String namespace,
            String originalFilename,
            byte[] fileBytes) {
        identity.verifyContentAdmin(actorId);

        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "File must not be empty.");
        }
        if (fileBytes.length > MAX_FILE_SIZE) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "File size exceeds 5 MiB maximum limit.");
        }

        String fileSha256 = sha256Hex(fileBytes);
        String requestHash = sha256Hex("QUESTIONS_JSON:" + namespace + ":" + fileSha256);

        Optional<ImportJob> existing =
                jobRepository.findByCreatedByAndRequestId(actorId, requestId);
        if (existing.isPresent()) {
            ImportJob existingJob = existing.get();
            if (existingJob.getRequestHash().equals(requestHash)) {
                log.info(
                        "Replaying existing question bank import job {} for request {}",
                        existingJob.getId(),
                        requestId);
                return replay(existingJob, fileBytes);
            } else {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Request ID already used with different file or parameters.");
            }
        }

        Set<UUID> existingSources =
                new HashSet<>(
                        sourceRepository.findAll().stream()
                                .filter(ContentSource::availableForPublication)
                                .map(ContentSource::getId)
                                .toList());

        QuestionBankJsonParser.ParseResult parseResult =
                jsonParser.parse(fileBytes, existingSources);

        UUID jobId = UUID.randomUUID();
        String objectKey = "imports/" + jobId + "/input";

        ImportJob job =
                new ImportJob(
                        jobId,
                        actorId,
                        requestId,
                        requestHash,
                        "QUESTIONS_JSON",
                        namespace != null ? namespace : "default",
                        originalFilename != null ? originalFilename : "import.json",
                        mediaStorage.bucket(),
                        objectKey,
                        (long) fileBytes.length,
                        fileSha256,
                        Instant.now().plusSeconds(600));

        List<ImportJobRow> rows = new ArrayList<>(parseResult.rows().size());
        for (QuestionBankJsonParser.ParsedRow pr : parseResult.rows()) {
            rows.add(
                    new ImportJobRow(
                            jobId,
                            pr.rowNumber(),
                            pr.inputHash(),
                            pr.externalKey(),
                            pr.payloadJson(),
                            pr.status(),
                            pr.errorsJson()));
        }

        StorageAllocation allocation =
                StorageAllocation.forImportJob(
                        UUID.randomUUID(),
                        TEMP_POOL_ID,
                        "import:" + jobId,
                        jobId,
                        (long) fileBytes.length,
                        "RESERVED",
                        mediaStorage.bucket(),
                        objectKey);

        return storeInput(job, rows, allocation, fileBytes);
    }

    private ContentEvents.CreateImportJobResult storeInput(
            ImportJob job, List<ImportJobRow> rows, StorageAllocation allocation, byte[] bytes) {
        if (!mediaStorage.isAvailable()) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Import storage is disabled.");
        }
        try {
            txService.createJobWithRows(job, rows, allocation);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            var existing =
                    jobRepository.findByCreatedByAndRequestId(
                            job.getCreatedBy(), job.getRequestId());
            if (existing.isEmpty()) {
                throw ex;
            }
            if (!existing.get().getRequestHash().equals(job.getRequestHash())) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Request ID already used with different file or parameters.");
            }
            return replay(existing.get(), bytes);
        }

        writeInput(job, bytes);
        verifyStoredInput(job);
        return new ContentEvents.CreateImportJobResult(
                false, toView(txService.finishUpload(job.getId(), job.getCreatedBy())));
    }

    private void writeInput(ImportJob job, byte[] bytes) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("import-upload-", ".bin");
            Files.write(tempFile, bytes);
            String mime =
                    "QUESTIONS_JSON".equals(job.getImportType()) ? "application/json" : "text/csv";
            mediaStorage.putIfAbsent(job.getInputObjectKey(), tempFile, mime, job.getInputSha256());
        } catch (java.io.IOException | com.masterenglish.api.common.storage.StorageException ex) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Could not confirm import input storage.");
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (java.io.IOException ex) {
                    log.warn("Could not remove local scratch file for import job {}", job.getId());
                }
            }
        }
    }

    private ContentEvents.CreateImportJobResult replay(ImportJob job, byte[] bytes) {
        if ("UPLOADING".equals(job.getStatus())) {
            if (!Instant.now().isBefore(job.getUploadExpiresAt())) {
                throw new BusinessException(ErrorCode.CONFLICT, "Import upload expired.");
            }
            try {
                verifyStoredInput(job);
            } catch (com.masterenglish.api.common.storage.StorageObjectNotFoundException ex) {
                writeInput(job, bytes);
                verifyStoredInput(job);
            }
            job = txService.finishUpload(job.getId(), job.getCreatedBy());
        }
        return new ContentEvents.CreateImportJobResult(true, toView(job));
    }

    private void verifyStoredInput(ImportJob job) {
        try (var input = mediaStorage.openStream(job.getInputObjectKey())) {
            byte[] stored = input.readNBytes(Math.toIntExact(job.getDeclaredSizeBytes()) + 1);
            if (stored.length != job.getDeclaredSizeBytes()
                    || !sha256Hex(stored).equals(job.getInputSha256())) {
                throw new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE, "Could not confirm import input storage.");
            }
        } catch (java.io.IOException ex) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Could not confirm import input storage.");
        }
    }

    @Override
    public ContentEvents.ImportJobView getJob(UUID actorId, UUID jobId) {
        identity.verifyContentAdmin(actorId);
        ImportJob job =
                jobRepository
                        .findById(jobId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Import job not found: " + jobId));

        if (!job.getCreatedBy().equals(actorId)) {
            // Cross-owner 404
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Import job not found: " + jobId);
        }

        return toView(job);
    }

    @Override
    public PageResponse<ContentEvents.ImportJobRowView> getJobRows(
            UUID actorId, UUID jobId, String status, Pageable pageable) {
        identity.verifyContentAdmin(actorId);
        ImportJob job =
                jobRepository
                        .findById(jobId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Import job not found: " + jobId));

        if (!job.getCreatedBy().equals(actorId)) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Import job not found: " + jobId);
        }

        Page<ImportJobRow> page =
                (status != null && !status.isBlank())
                        ? rowRepository.findByJobIdAndStatusOrderByRowNumberAsc(
                                jobId, status.toUpperCase(), pageable)
                        : rowRepository.findByJobIdOrderByRowNumberAsc(jobId, pageable);

        List<ContentEvents.ImportJobRowView> rowViews =
                page.getContent().stream()
                        .map(
                                r ->
                                        new ContentEvents.ImportJobRowView(
                                                r.getJobId(),
                                                r.getRowNumber(),
                                                r.getExternalKey(),
                                                r.getStatus(),
                                                r.getResultContentItemId(),
                                                r.getErrors(),
                                                r.getProcessedAt()))
                        .toList();

        return new PageResponse<>(
                rowViews,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    @Override
    public ContentEvents.ConfirmImportJobResult confirmJob(UUID actorId, UUID jobId) {
        identity.verifyContentAdmin(actorId);

        ImportJob job = txService.claimForConfirm(jobId, actorId);

        if ("COMPLETED".equals(job.getStatus())
                || "PARTIAL_SUCCESS".equals(job.getStatus())
                || "FAILED".equals(job.getStatus())) {
            return new ContentEvents.ConfirmImportJobResult(
                    job.getId(),
                    job.getStatus(),
                    job.getTotalRows(),
                    job.getSuccessRows(),
                    job.getFailedRows(),
                    job.getSkippedRows());
        }

        List<ImportJobRow> rows = rowRepository.findByJobIdOrderByRowNumberAsc(jobId);
        int success = 0;
        int failed = 0;
        int skipped = 0;

        for (ImportJobRow row : rows) {
            if ("SUCCESS".equals(row.getStatus())) {
                success++;
                continue;
            }
            if ("INVALID".equals(row.getStatus()) || "FAILED".equals(row.getStatus())) {
                failed++;
                continue;
            }
            if ("SKIPPED".equals(row.getStatus())) {
                skipped++;
                continue;
            }

            // Valid or Pending row
            try {
                UUID sourceId = null;
                String cleanPayload = row.getPayload();
                try {
                    var payloadNode = mapper.readTree(row.getPayload());
                    if (payloadNode
                                    instanceof
                                    com.fasterxml.jackson.databind.node.ObjectNode objNode
                            && objNode.hasNonNull("source_id")) {
                        sourceId = UUID.fromString(objNode.get("source_id").asText());
                        objNode.remove("source_id");
                        cleanPayload = objNode.toString();
                    }
                } catch (Exception ignored) {
                }

                if ("QUESTIONS_JSON".equals(job.getImportType())) {
                    txService.processQuestionBankRow(
                            jobId,
                            job.getClaimToken(),
                            row.getRowNumber(),
                            actorId,
                            job.getImportNamespace(),
                            row.getExternalKey(),
                            cleanPayload,
                            sourceId);
                } else {
                    txService.processWordRow(
                            jobId,
                            job.getClaimToken(),
                            row.getRowNumber(),
                            actorId,
                            job.getImportNamespace(),
                            row.getExternalKey(),
                            cleanPayload,
                            sourceId);
                }
                success++;
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.ACCESS_DENIED
                        || e.getErrorCode() == ErrorCode.UNAUTHENTICATED
                        || (e.getErrorCode() == ErrorCode.CONFLICT
                                && "Import job claim is no longer active."
                                        .equals(e.getMessage()))) {
                    throw e;
                }
                log.warn("Row {} business failure: {}", row.getRowNumber(), e.getErrorCode());
                failed++;
                ArrayNode errNode = mapper.createArrayNode();
                String safeMessage =
                        e.getErrorCode() == ErrorCode.INTERNAL_ERROR
                                ? "Row processing failed due to an internal error."
                                : e.getMessage();
                errNode.addObject().put("field", "row").put("message", safeMessage);
                txService.markRowFailed(
                        jobId, job.getClaimToken(), row.getRowNumber(), errNode.toString());
            } catch (Exception e) {
                log.warn(
                        "Row {} execution failed with {}",
                        row.getRowNumber(),
                        e.getClass().getSimpleName());
                failed++;
                ArrayNode errNode = mapper.createArrayNode();
                errNode.addObject()
                        .put("field", "row")
                        .put("message", "Row processing failed due to an internal error.");
                txService.markRowFailed(
                        jobId, job.getClaimToken(), row.getRowNumber(), errNode.toString());
            }
        }

        ImportJob finalized =
                txService.finalizeJob(jobId, job.getClaimToken(), success, failed, skipped);

        return new ContentEvents.ConfirmImportJobResult(
                finalized.getId(),
                finalized.getStatus(),
                finalized.getTotalRows(),
                finalized.getSuccessRows(),
                finalized.getFailedRows(),
                finalized.getSkippedRows());
    }

    private ContentEvents.ImportJobView toView(ImportJob j) {
        return new ContentEvents.ImportJobView(
                j.getId(),
                j.getCreatedBy(),
                j.getRequestId(),
                j.getRequestHash(),
                j.getImportType(),
                j.getImportNamespace(),
                j.getOriginalFilename(),
                j.getDeclaredSizeBytes(),
                j.getInputSha256(),
                j.getUploadExpiresAt(),
                j.getStatus(),
                j.getTotalRows(),
                j.getSuccessRows(),
                j.getFailedRows(),
                j.getSkippedRows(),
                j.getConfirmedAt(),
                j.getFinishedAt(),
                j.getFailureCode(),
                j.getCreatedAt(),
                j.getUpdatedAt());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String str) {
        return sha256Hex(str.getBytes(StandardCharsets.UTF_8));
    }
}
