package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.modules.content.service.ContentAdminService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ContentAdminEventHandler {
    private final ContentAdminService admin;

    ContentAdminEventHandler(ContentAdminService admin) {
        this.admin = admin;
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
}
