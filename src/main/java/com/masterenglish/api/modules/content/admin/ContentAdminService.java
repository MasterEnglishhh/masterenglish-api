package com.masterenglish.api.modules.content.admin;

import com.masterenglish.api.common.api.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface ContentAdminService {

    PageResponse<AdminContentItemDto> findContentItems(
            String search, String contentType, String status, Pageable pageable);

    AdminContentItemDto createWordDraft(CreateWordDraftRequest request, UUID actorId);

    AdminContentItemDto createDeckDraft(CreateDeckDraftRequest request, UUID actorId);

    AdminRevisionDto submitRevision(UUID revisionId, UUID actorId);

    AdminRevisionDto approveRevision(UUID revisionId, String reviewNote, UUID actorId);

    AdminRevisionDto rejectRevision(UUID revisionId, String reviewNote, UUID actorId);

    AdminRevisionDto publishRevision(UUID revisionId, UUID actorId);

    void archiveContentItem(UUID itemId, String reason, UUID actorId);

    PageResponse<ContentSourceDto> findSources(String search, Pageable pageable);

    ContentSourceDto createSource(CreateContentSourceRequest request, UUID actorId);

    PageResponse<AuditLogEntryDto> findAuditLogs(
            String search, String entityType, Pageable pageable);

    void recordAuditLog(
            UUID actorId, String action, String entityType, UUID entityId, String metadata, String reason);

    long getStorageReadyBytes();

    long getStorageReadyCount();

    List<AuditLogEntryDto> getRecentActivities(int limit);
}
