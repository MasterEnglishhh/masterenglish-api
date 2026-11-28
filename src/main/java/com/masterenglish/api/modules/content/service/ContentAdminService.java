package com.masterenglish.api.modules.content.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.AdminSource;
import com.masterenglish.api.common.events.ContentEvents.AuditEntry;
import com.masterenglish.api.common.events.ContentEvents.DeckDraft;
import com.masterenglish.api.common.events.ContentEvents.DraftEdit;
import com.masterenglish.api.common.events.ContentEvents.GrammarDraft;
import com.masterenglish.api.common.events.ContentEvents.ItemDetailView;
import com.masterenglish.api.common.events.ContentEvents.ItemView;
import com.masterenglish.api.common.events.ContentEvents.QuestionDraft;
import com.masterenglish.api.common.events.ContentEvents.RevisionClone;
import com.masterenglish.api.common.events.ContentEvents.RevisionHistoryView;
import com.masterenglish.api.common.events.ContentEvents.RevisionView;
import com.masterenglish.api.common.events.ContentEvents.SavedGrammarDraft;
import com.masterenglish.api.common.events.ContentEvents.SourceView;
import com.masterenglish.api.common.events.ContentEvents.UpdateSource;
import com.masterenglish.api.common.events.ContentEvents.ValidationView;
import com.masterenglish.api.common.events.ContentEvents.WordDraft;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface ContentAdminService {

    PageResponse<ItemView> findContentItems(
            String search, String contentType, String status, Pageable pageable);

    PageResponse<ItemView> findContentItems(
            String domain, String search, String contentType, String status, Pageable pageable);

    ItemDetailView getContentItemDetail(UUID itemId);

    PageResponse<RevisionHistoryView> getRevisionHistory(UUID itemId, Pageable pageable);

    ItemView createWordDraft(WordDraft request, UUID actorId);

    ItemView createDeckDraft(DeckDraft request, UUID actorId);

    ItemView saveGrammarDraft(SavedGrammarDraft request, UUID actorId);

    ItemView createGrammarTopicDraft(GrammarDraft request, UUID actorId);

    ItemView createGrammarLessonDraft(GrammarDraft request, UUID actorId);

    ItemView createQuestionDraft(QuestionDraft request, UUID actorId);

    ItemView createQuestionGroupDraft(ContentEvents.QuestionGroupDraft request, UUID actorId);

    ItemView createPracticeSetDraft(ContentEvents.PracticeSetDraft request, UUID actorId);

    RevisionView updateDraftRevision(DraftEdit request, UUID actorId);

    RevisionView createRevisionFromPublished(RevisionClone request, UUID actorId);

    ValidationView validateRevision(UUID revisionId);

    RevisionView submitRevision(UUID revisionId, UUID actorId);

    RevisionView approveRevision(UUID revisionId, String reviewNote, UUID actorId);

    RevisionView rejectRevision(UUID revisionId, String reviewNote, UUID actorId);

    RevisionView publishRevision(UUID revisionId, UUID actorId);

    void archiveContentItem(UUID itemId, String reason, UUID actorId);

    void unpublishContentItem(UUID itemId, String reason, UUID actorId);

    PageResponse<SourceView> findSources(String search, Pageable pageable);

    SourceView createSource(AdminSource request, UUID actorId);

    SourceView updateSource(UpdateSource request, UUID actorId);

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

    ContentEvents.UploadRequestView reserveMediaUpload(
            UUID actorId, ContentEvents.ReserveUploadRequest request);

    ContentEvents.StageResult stageMediaUpload(
            UUID actorId,
            UUID uploadRequestId,
            java.io.InputStream inputStream,
            String contentType);

    ContentEvents.CompleteResult completeMediaUpload(
            UUID actorId, UUID uploadRequestId, boolean sync);

    ContentEvents.UploadStatusView getMediaUploadStatus(UUID actorId, UUID uploadRequestId);

    ContentEvents.MediaAssetView getMediaAsset(UUID actorId, UUID assetId);

    ContentEvents.MediaAssetView archiveMediaAsset(UUID actorId, UUID assetId);
}
