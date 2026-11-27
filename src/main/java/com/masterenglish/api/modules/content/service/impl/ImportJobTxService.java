package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.content.dto.response.ContentItemResponse;
import com.masterenglish.api.modules.content.entity.ImportJob;
import com.masterenglish.api.modules.content.entity.ImportJobRow;
import com.masterenglish.api.modules.content.entity.StorageAllocation;
import com.masterenglish.api.modules.content.repository.ImportJobRepository;
import com.masterenglish.api.modules.content.repository.ImportJobRowRepository;
import com.masterenglish.api.modules.content.repository.StorageAllocationRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ImportJobTxService {

    private final ImportJobRepository jobRepository;
    private final ImportJobRowRepository rowRepository;
    private final StorageAllocationRepository allocationRepository;
    private final ContentAuthoringService authoringService;
    private final IdentityEvents identityEvents;
    private final ObjectMapper mapper;

    public ImportJobTxService(
            ImportJobRepository jobRepository,
            ImportJobRowRepository rowRepository,
            StorageAllocationRepository allocationRepository,
            ContentAuthoringService authoringService,
            IdentityEvents identityEvents,
            ObjectMapper mapper) {
        this.jobRepository = jobRepository;
        this.rowRepository = rowRepository;
        this.allocationRepository = allocationRepository;
        this.authoringService = authoringService;
        this.identityEvents = identityEvents;
        this.mapper = mapper;
    }

    @Transactional
    public ImportJob createJobWithRows(
            ImportJob job, List<ImportJobRow> rows, StorageAllocation allocation) {
        ImportJob savedJob = jobRepository.save(job);
        rowRepository.saveAll(rows);
        allocationRepository.save(allocation);
        return savedJob;
    }

    @Transactional
    public ImportJob finishUpload(UUID jobId, UUID actorId) {
        ImportJob job = jobRepository.findByIdForUpdate(jobId).orElseThrow();
        identityEvents.verifyContentAdmin(actorId);
        if (!job.getCreatedBy().equals(actorId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Import job not found.");
        }
        if ("UPLOADING".equals(job.getStatus())) {
            Instant now = Instant.now();
            if (!now.isBefore(job.getUploadExpiresAt())) {
                throw new BusinessException(ErrorCode.CONFLICT, "Import upload expired.");
            }
            int valid = (int) rowRepository.countByJobIdAndStatus(jobId, "VALID");
            int invalid = (int) rowRepository.countByJobIdAndStatus(jobId, "INVALID");
            if (valid > 0) {
                job.markReady(valid + invalid, invalid, now);
            } else {
                job.markFailed("ALL_ROWS_INVALID", now);
            }
        }
        return job;
    }

    @Transactional
    public ImportJob claimForConfirm(UUID jobId, UUID actorId) {
        ImportJob job =
                jobRepository
                        .findByIdForUpdate(jobId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Import job not found: " + jobId));

        if (!job.getCreatedBy().equals(actorId)) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Import job not found: " + jobId);
        }

        if ("COMPLETED".equals(job.getStatus())
                || "PARTIAL_SUCCESS".equals(job.getStatus())
                || "FAILED".equals(job.getStatus())) {
            return job;
        }

        if ("PROCESSING".equals(job.getStatus())) {
            Instant now = Instant.now();
            if (job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(now)) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Import job is currently being processed by another worker.");
            }
        }

        if (!"READY".equals(job.getStatus()) && !"PROCESSING".equals(job.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Import job is not ready for confirmation: " + job.getStatus());
        }

        Instant now = Instant.now();
        UUID claimToken = UUID.randomUUID();
        Instant leaseUntil = now.plusSeconds(300);
        job.startProcessing(now, claimToken, leaseUntil);
        return jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processWordRow(
            UUID jobId,
            UUID claimToken,
            int rowNumber,
            UUID actorId,
            String namespace,
            String externalKey,
            String payloadJson,
            UUID sourceId) {
        lockActiveClaim(jobId, claimToken);
        identityEvents.verifyContentAdmin(actorId);

        ImportJobRow row =
                rowRepository
                        .findByJobIdAndRowNumber(jobId, rowNumber)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Row not found: " + jobId + ", " + rowNumber));

        if ("SUCCESS".equals(row.getStatus())) {
            return true;
        }

        ContentItemResponse item =
                authoringService.createItemWithDraft(
                        UUID.randomUUID(),
                        "WORD",
                        actorId,
                        namespace,
                        externalKey,
                        payloadJson,
                        "Imported from CSV row " + rowNumber,
                        sourceId != null ? List.of(sourceId) : List.of());

        row.markSuccess(item.getId(), null, Instant.now());
        rowRepository.save(row);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processQuestionBankRow(
            UUID jobId,
            UUID claimToken,
            int rowNumber,
            UUID actorId,
            String namespace,
            String externalKey,
            String payloadJson,
            UUID sourceId) {
        lockActiveClaim(jobId, claimToken);
        identityEvents.verifyContentAdmin(actorId);

        ImportJobRow row =
                rowRepository
                        .findByJobIdAndRowNumber(jobId, rowNumber)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Row not found: " + jobId + ", " + rowNumber));

        if ("SUCCESS".equals(row.getStatus())) {
            return true;
        }

        try {
            JsonNode itemNode = mapper.readTree(payloadJson);
            String type = itemNode.hasNonNull("type") ? itemNode.path("type").asText() : null;
            if (type == null) {
                if (itemNode.has("children") || itemNode.has("group_type")) {
                    type = "QUESTION_GROUP";
                } else {
                    type = "QUESTION";
                }
            }

            if ("QUESTION".equals(type)) {
                ObjectNode cleanData = (ObjectNode) itemNode.deepCopy();
                cleanData.remove("type");
                cleanData.remove("external_key");
                cleanData.remove("source_id");

                ContentItemResponse item =
                        authoringService.createItemWithDraft(
                                UUID.randomUUID(),
                                "QUESTION",
                                actorId,
                                namespace,
                                externalKey,
                                cleanData.toString(),
                                "Imported Question from JSON row " + rowNumber,
                                sourceId != null ? List.of(sourceId) : List.of());

                row.markSuccess(item.getId(), null, Instant.now());
                rowRepository.save(row);
                return true;
            } else {
                // QUESTION_GROUP
                ObjectNode cleanGroupData = (ObjectNode) itemNode.deepCopy();
                cleanGroupData.remove("type");
                cleanGroupData.remove("external_key");
                cleanGroupData.remove("source_id");

                if (cleanGroupData.has("children") && cleanGroupData.path("children").isArray()) {
                    ArrayNode childrenArr = (ArrayNode) cleanGroupData.path("children");
                    for (int i = 0; i < childrenArr.size(); i++) {
                        JsonNode child = childrenArr.get(i);
                        if (child.hasNonNull("prompt")) {
                            // Inline child question
                            String childExtKey =
                                    child.hasNonNull("external_key")
                                            ? child.path("external_key").asText()
                                            : externalKey + "#" + (i + 1);

                            ObjectNode childClean = (ObjectNode) child.deepCopy();
                            childClean.remove("external_key");
                            UUID childSourceId = sourceId;
                            if (childClean.hasNonNull("source_id")) {
                                try {
                                    childSourceId =
                                            UUID.fromString(childClean.path("source_id").asText());
                                } catch (Exception ignored) {
                                }
                            }
                            childClean.remove("source_id");

                            ContentItemResponse childItem =
                                    authoringService.createItemWithDraft(
                                            UUID.randomUUID(),
                                            "QUESTION",
                                            actorId,
                                            namespace,
                                            childExtKey,
                                            childClean.toString(),
                                            "Imported group child " + childExtKey,
                                            childSourceId != null
                                                    ? List.of(childSourceId)
                                                    : List.of());

                            ObjectNode updatedChildRef = mapper.createObjectNode();
                            updatedChildRef.put("question_id", childItem.getId().toString());
                            updatedChildRef.put(
                                    "position",
                                    child.hasNonNull("position")
                                            ? child.path("position").asInt()
                                            : i + 1);
                            if (child.hasNonNull("blank_key")) {
                                updatedChildRef.put("blank_key", child.path("blank_key").asText());
                            }
                            childrenArr.set(i, updatedChildRef);
                        }
                    }
                }

                ContentItemResponse groupItem =
                        authoringService.createItemWithDraft(
                                UUID.randomUUID(),
                                "QUESTION_GROUP",
                                actorId,
                                namespace,
                                externalKey,
                                cleanGroupData.toString(),
                                "Imported Question Group from JSON row " + rowNumber,
                                sourceId != null ? List.of(sourceId) : List.of());

                row.markSuccess(groupItem.getId(), null, Instant.now());
                rowRepository.save(row);
                return true;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Row {} execution failed with {}", rowNumber, e.getClass().getSimpleName());
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "Row processing failed due to an internal error.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRowFailed(UUID jobId, UUID claimToken, int rowNumber, String errorsJson) {
        ImportJob job = lockActiveClaim(jobId, claimToken);
        identityEvents.verifyContentAdmin(job.getCreatedBy());
        ImportJobRow row = rowRepository.findByJobIdAndRowNumber(jobId, rowNumber).orElse(null);
        if (row != null && !"SUCCESS".equals(row.getStatus())) {
            row.markFailed(errorsJson, Instant.now());
            rowRepository.save(row);
        }
    }

    @Transactional
    public ImportJob finalizeJob(
            UUID jobId,
            UUID claimToken,
            int ignoredSuccess,
            int ignoredFailed,
            int ignoredSkipped) {
        ImportJob job = lockActiveClaim(jobId, claimToken);
        identityEvents.verifyContentAdmin(job.getCreatedBy());
        int success = (int) rowRepository.countByJobIdAndStatus(jobId, "SUCCESS");
        int failed =
                (int)
                        (rowRepository.countByJobIdAndStatus(jobId, "FAILED")
                                + rowRepository.countByJobIdAndStatus(jobId, "INVALID"));
        int skipped = (int) rowRepository.countByJobIdAndStatus(jobId, "SKIPPED");
        job.finishProcessing(success, failed, skipped, Instant.now());
        // Retain TEMP quota until cleanup confirms the input object was deleted.
        return jobRepository.save(job);
    }

    private ImportJob lockActiveClaim(UUID jobId, UUID claimToken) {
        ImportJob job =
                jobRepository
                        .findByIdForUpdate(jobId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND, "Job not found"));

        if (!"PROCESSING".equals(job.getStatus())
                || claimToken == null
                || !Objects.equals(job.getClaimToken(), claimToken)
                || job.getLeaseUntil() == null
                || !job.getLeaseUntil().isAfter(Instant.now())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Import job claim is no longer active.");
        }
        return job;
    }
}
