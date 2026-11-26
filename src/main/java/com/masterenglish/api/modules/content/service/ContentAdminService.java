package com.masterenglish.api.modules.content.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.ContentEvents.AdminSource;
import com.masterenglish.api.common.events.ContentEvents.AuditEntry;
import com.masterenglish.api.common.events.ContentEvents.DeckDraft;
import com.masterenglish.api.common.events.ContentEvents.GrammarDraft;
import com.masterenglish.api.common.events.ContentEvents.ItemView;
import com.masterenglish.api.common.events.ContentEvents.RevisionView;
import com.masterenglish.api.common.events.ContentEvents.SourceView;
import com.masterenglish.api.common.events.ContentEvents.WordDraft;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface ContentAdminService {

    PageResponse<ItemView> findContentItems(
            String search, String contentType, String status, String domain, Pageable pageable);

    ItemView createWordDraft(WordDraft request, UUID actorId);

    ItemView createDeckDraft(DeckDraft request, UUID actorId);

    ItemView saveGrammarDraft(GrammarDraft request, UUID actorId);

    RevisionView submitRevision(UUID revisionId, UUID actorId);

    RevisionView approveRevision(UUID revisionId, String reviewNote, UUID actorId);

    RevisionView rejectRevision(UUID revisionId, String reviewNote, UUID actorId);

    RevisionView publishRevision(UUID revisionId, UUID actorId);

    void archiveContentItem(UUID itemId, String reason, UUID actorId);

    PageResponse<SourceView> findSources(String search, Pageable pageable);

    SourceView createSource(AdminSource request, UUID actorId);

    PageResponse<AuditEntry> findAuditLogs(String search, String entityType, Pageable pageable);

    void recordAuditLog(
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            String metadata,
            String reason);

    long getStorageReadyBytes();

    long getStorageReadyCount();

    List<AuditEntry> getRecentActivities(int limit);

    long countTotalContentItems();

    long countPendingReviewRevisions();
}
