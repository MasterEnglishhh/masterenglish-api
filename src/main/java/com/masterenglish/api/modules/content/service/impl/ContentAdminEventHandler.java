package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.modules.content.service.ContentAdminService;
import com.masterenglish.api.modules.content.service.ImportJobService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ContentAdminEventHandler {
    private final ContentAdminService admin;
    private final ImportJobService importJobs;

    ContentAdminEventHandler(ContentAdminService admin, ImportJobService importJobs) {
        this.admin = admin;
        this.importJobs = importJobs;
    }

    @EventListener
    void on(ContentEvents.AdminFindContentItems event) {
        event.reply()
                .complete(
                        admin.findContentItems(
                                event.domain(),
                                event.search(),
                                event.contentType(),
                                event.status(),
                                event.pageable()));
    }

    @EventListener
    void on(ContentEvents.AdminGetContentItemDetail event) {
        event.reply().complete(admin.getContentItemDetail(event.itemId()));
    }

    @EventListener
    void on(ContentEvents.AdminGetRevisionHistory event) {
        event.reply().complete(admin.getRevisionHistory(event.itemId(), event.pageable()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateWordDraft event) {
        event.reply().complete(admin.createWordDraft(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateDeckDraft event) {
        event.reply().complete(admin.createDeckDraft(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateGrammarTopicDraft event) {
        event.reply().complete(admin.createGrammarTopicDraft(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateGrammarLessonDraft event) {
        event.reply().complete(admin.createGrammarLessonDraft(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateQuestionDraft event) {
        event.reply().complete(admin.createQuestionDraft(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateQuestionGroupDraft event) {
        event.reply().complete(admin.createQuestionGroupDraft(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminCreatePracticeSetDraft event) {
        event.reply().complete(admin.createPracticeSetDraft(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminUpdateDraftRevision event) {
        event.reply().complete(admin.updateDraftRevision(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateRevisionFromPublished event) {
        event.reply().complete(admin.createRevisionFromPublished(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminValidateRevision event) {
        event.reply().complete(admin.validateRevision(event.revisionId()));
    }

    @EventListener
    void on(ContentEvents.AdminSubmitRevision event) {
        event.reply().complete(admin.submitRevision(event.revisionId(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminApproveRevision event) {
        event.reply()
                .complete(
                        admin.approveRevision(
                                event.revisionId(), event.reviewNote(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminRejectRevision event) {
        event.reply()
                .complete(
                        admin.rejectRevision(
                                event.revisionId(), event.reviewNote(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminPublishRevision event) {
        event.reply().complete(admin.publishRevision(event.revisionId(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminArchiveContentItem event) {
        admin.archiveContentItem(event.itemId(), event.reason(), event.actorId());
        event.reply().complete(null);
    }

    @EventListener
    void on(ContentEvents.AdminUnpublishContentItem event) {
        admin.unpublishContentItem(event.itemId(), event.reason(), event.actorId());
        event.reply().complete(null);
    }

    @EventListener
    void on(ContentEvents.AdminFindSources event) {
        event.reply().complete(admin.findSources(event.search(), event.pageable()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateSource event) {
        event.reply().complete(admin.createSource(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminUpdateSource event) {
        event.reply().complete(admin.updateSource(event.request(), event.actorId()));
    }

    @EventListener
    void on(ContentEvents.AdminFindAuditLogs event) {
        event.reply()
                .complete(
                        admin.findAuditLogs(event.search(), event.entityType(), event.pageable()));
    }

    @EventListener
    void on(ContentEvents.AdminRecordAuditLog event) {
        admin.recordAuditLog(
                event.actorId(),
                event.action(),
                event.entityType(),
                event.entityId(),
                event.metadata(),
                event.reason());
        event.reply().complete(null);
    }

    @EventListener
    void on(ContentEvents.AdminGetStorageReadyBytes event) {
        event.reply().complete(admin.getStorageReadyBytes());
    }

    @EventListener
    void on(ContentEvents.AdminGetStorageReadyCount event) {
        event.reply().complete(admin.getStorageReadyCount());
    }

    @EventListener
    void on(ContentEvents.AdminGetRecentActivities event) {
        event.reply().complete(admin.getRecentActivities(event.limit()));
    }

    @EventListener
    void on(ContentEvents.AdminCountTotalContentItems event) {
        event.reply().complete(admin.countTotalContentItems());
    }

    @EventListener
    void on(ContentEvents.AdminCountPendingReviewRevisions event) {
        event.reply().complete(admin.countPendingReviewRevisions());
    }

    @EventListener
    void on(ContentEvents.AdminReserveMediaUpload event) {
        event.reply().complete(admin.reserveMediaUpload(event.actorId(), event.request()));
    }

    @EventListener
    void on(ContentEvents.AdminStageMediaUpload event) {
        event.reply()
                .complete(
                        admin.stageMediaUpload(
                                event.actorId(),
                                event.uploadRequestId(),
                                event.inputStream(),
                                event.contentType()));
    }

    @EventListener
    void on(ContentEvents.AdminCompleteMediaUpload event) {
        event.reply()
                .complete(
                        admin.completeMediaUpload(
                                event.actorId(), event.uploadRequestId(), event.sync()));
    }

    @EventListener
    void on(ContentEvents.AdminGetMediaUploadStatus event) {
        event.reply()
                .complete(admin.getMediaUploadStatus(event.actorId(), event.uploadRequestId()));
    }

    @EventListener
    void on(ContentEvents.AdminGetMediaAsset event) {
        event.reply().complete(admin.getMediaAsset(event.actorId(), event.assetId()));
    }

    @EventListener
    void on(ContentEvents.AdminArchiveMediaAsset event) {
        event.reply().complete(admin.archiveMediaAsset(event.actorId(), event.assetId()));
    }

    @EventListener
    void on(ContentEvents.AdminCreateImportJob event) {
        if ("QUESTION_BANK".equalsIgnoreCase(event.module())) {
            event.reply()
                    .complete(
                            importJobs.createQuestionBankJsonImportJob(
                                    event.actorId(),
                                    event.requestId(),
                                    event.namespace(),
                                    event.originalFilename(),
                                    event.fileBytes()));
        } else {
            event.reply()
                    .complete(
                            importJobs.createWordCsvImportJob(
                                    event.actorId(),
                                    event.requestId(),
                                    event.namespace(),
                                    event.originalFilename(),
                                    event.fileBytes()));
        }
    }

    @EventListener
    void on(ContentEvents.AdminGetImportJob event) {
        event.reply().complete(importJobs.getJob(event.actorId(), event.jobId()));
    }

    @EventListener
    void on(ContentEvents.AdminGetImportJobRows event) {
        event.reply()
                .complete(
                        importJobs.getJobRows(
                                event.actorId(), event.jobId(), event.status(), event.pageable()));
    }

    @EventListener
    void on(ContentEvents.AdminConfirmImportJob event) {
        event.reply().complete(importJobs.confirmJob(event.actorId(), event.jobId()));
    }
}
