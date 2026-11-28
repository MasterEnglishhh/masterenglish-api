package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageException;
import com.masterenglish.api.modules.content.entity.ImportJob;
import com.masterenglish.api.modules.content.entity.MediaAsset;
import com.masterenglish.api.modules.content.entity.MediaUploadGrant;
import com.masterenglish.api.modules.content.repository.ImportJobRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import com.masterenglish.api.modules.content.repository.StorageAllocationRepository;
import com.masterenglish.api.modules.content.repository.StoragePoolRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@EnableScheduling
public class CompletedMediaCleanup {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final MediaUploadGrantRepository grants;
    private final ImportJobRepository importJobs;
    private final MediaAssetRepository assets;
    private final StorageAllocationRepository allocations;
    private final StoragePoolRepository pools;
    private final MediaStorage storage;
    private final TransactionTemplate transactions;

    public CompletedMediaCleanup(
            MediaUploadGrantRepository grants,
            ImportJobRepository importJobs,
            MediaAssetRepository assets,
            StorageAllocationRepository allocations,
            StoragePoolRepository pools,
            MediaStorage storage,
            TransactionTemplate transactions) {
        this.grants = grants;
        this.importJobs = importJobs;
        this.assets = assets;
        this.allocations = allocations;
        this.pools = pools;
        this.storage = storage;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void run() {
        runAt(grants.getDbTimestamps().getWallTime());
    }

    public void runAt(Instant now) {
        if (!storage.isAvailable()) {
            return;
        }
        expirePendingGrants(now);
        failDeadlineExceededVerifyingGrants(now);
        expireUploadingImportJobs(now);
        cleanupMediaTempObjects(now);
        cleanupImportTempObjects(now);
    }

    private void expirePendingGrants(Instant now) {
        for (MediaUploadGrant candidate :
                grants.findExpiredPendingGrants(now, PageRequest.of(0, BATCH_SIZE))) {
            transactions.executeWithoutResult(
                    status -> {
                        MediaUploadGrant grant =
                                grants.findByIdForUpdate(candidate.getId()).orElse(null);
                        if (grant == null
                                || !"PENDING".equals(grant.getStatus())
                                || grant.getExpiresAt().isAfter(now)) {
                            return;
                        }
                        MediaAsset asset = assets.findById(grant.getMediaId()).orElse(null);
                        if (asset != null
                                && !"READY".equals(asset.getStatus())
                                && !"ARCHIVED".equals(asset.getStatus())) {
                            asset.markFailed("UPLOAD_EXPIRED");
                        }
                        grant.markExpired(now);
                    });
        }
    }

    private void failDeadlineExceededVerifyingGrants(Instant now) {
        if (grants.countOccupiedPhysicalSlots() > 0) {
            return;
        }
        for (MediaUploadGrant candidate :
                grants.findDeadlineExceededVerifyingGrants(now, PageRequest.of(0, BATCH_SIZE))) {
            transactions.executeWithoutResult(
                    status -> {
                        if (grants.countOccupiedPhysicalSlots() > 0) {
                            return;
                        }
                        MediaUploadGrant grant =
                                grants.findByIdForUpdate(candidate.getId()).orElse(null);
                        if (grant == null
                                || !"VERIFYING".equals(grant.getStatus())
                                || grant.getVerificationDeadlineAt() == null
                                || grant.getVerificationDeadlineAt().isAfter(now)) {
                            return;
                        }
                        MediaAsset asset = assets.findById(grant.getMediaId()).orElse(null);
                        if (asset != null
                                && !"READY".equals(asset.getStatus())
                                && !"ARCHIVED".equals(asset.getStatus())) {
                            asset.markFailed("VERIFICATION_DEADLINE_EXCEEDED");
                        }
                        grant.markFailed("VERIFICATION_DEADLINE_EXCEEDED", now);
                    });
        }
    }

    private void expireUploadingImportJobs(Instant now) {
        for (ImportJob candidate :
                importJobs.findExpiredUploadingJobs(now, PageRequest.of(0, BATCH_SIZE))) {
            transactions.executeWithoutResult(
                    status -> {
                        ImportJob job =
                                importJobs.findByIdForUpdate(candidate.getId()).orElse(null);
                        if (job == null
                                || !"UPLOADING".equals(job.getStatus())
                                || job.getUploadExpiresAt().isAfter(now)
                                || job.getClaimToken() != null) {
                            return;
                        }
                        job.markExpired(now);
                    });
        }
    }

    private void cleanupMediaTempObjects(Instant now) {
        var tempPool = MediaReservationServiceImpl.TEMP_POOL_ID;
        Instant cutoff = now.minus(Duration.ofHours(24));
        int page = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<MediaUploadGrant> candidates =
                    grants.findCompletedForCleanup(
                            tempPool,
                            cutoff,
                            now,
                            storage.bucket(),
                            PageRequest.of(page, BATCH_SIZE));
            if (candidates.isEmpty()) {
                break;
            }
            int releasedInBatch = 0;
            for (MediaUploadGrant grant : candidates) {
                // An unresolved staging claim can still PUT after deletion; retention is not
                // fencing.
                if (grant.getPutClaimToken() != null && grant.getStagedAt() == null) {
                    continue;
                }
                String key = grant.getTempObjectKey();
                if (!deleteAndVerifyAbsent(key, "TEMP media", grant.getId())) {
                    continue;
                }
                Boolean released =
                        transactions.execute(
                                status -> {
                                    pools.findByIdForUpdate(tempPool).orElseThrow();
                                    return allocations
                                            .findByAllocationKey("req:" + grant.getId() + ":temp")
                                            .filter(
                                                    allocation ->
                                                            "RESERVED"
                                                                    .equals(allocation.getState()))
                                            .map(
                                                    allocation -> {
                                                        allocation.release(now);
                                                        return true;
                                                    })
                                            .orElse(false);
                                });
                if (Boolean.TRUE.equals(released)) {
                    releasedInBatch++;
                }
            }
            if (candidates.size() < BATCH_SIZE) {
                break;
            }
            if (releasedInBatch == 0) {
                page++;
            }
        }
    }

    private void cleanupImportTempObjects(Instant now) {
        var tempPool = MediaReservationServiceImpl.TEMP_POOL_ID;
        Instant cutoff = now.minus(Duration.ofHours(24));
        int page = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<ImportJob> candidates =
                    importJobs.findTerminalForCleanup(
                            tempPool,
                            cutoff,
                            now,
                            storage.bucket(),
                            PageRequest.of(page, BATCH_SIZE));
            if (candidates.isEmpty()) {
                break;
            }
            int releasedInBatch = 0;
            for (ImportJob job : candidates) {
                String key = job.getInputObjectKey();
                if (!deleteAndVerifyAbsent(key, "TEMP import", job.getId())) {
                    continue;
                }
                Boolean released =
                        transactions.execute(
                                status -> {
                                    pools.findByIdForUpdate(tempPool).orElseThrow();
                                    return allocations
                                            .findByAllocationKey("import:" + job.getId())
                                            .filter(
                                                    allocation ->
                                                            "RESERVED"
                                                                    .equals(allocation.getState()))
                                            .map(
                                                    allocation -> {
                                                        allocation.release(now);
                                                        return true;
                                                    })
                                            .orElse(false);
                                });
                if (Boolean.TRUE.equals(released)) {
                    releasedInBatch++;
                }
            }
            if (candidates.size() < BATCH_SIZE) {
                break;
            }
            if (releasedInBatch == 0) {
                page++;
            }
        }
    }

    private boolean deleteAndVerifyAbsent(String key, String kind, Object id) {
        try {
            return storage.delete(key) || !storage.exists(key);
        } catch (StorageException ex) {
            log.warn("Could not confirm {} cleanup for {}", kind, id);
            return false;
        }
    }
}
