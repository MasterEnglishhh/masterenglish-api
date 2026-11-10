package com.masterenglish.api.modules.content.query;

import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContentQueryServiceImpl implements ContentQueryService {

    private final ContentItemRepository itemRepository;
    private final com.masterenglish.api.modules.content.repository.ContentRevisionRepository revisionRepository;

    public ContentQueryServiceImpl(
            ContentItemRepository itemRepository,
            com.masterenglish.api.modules.content.repository.ContentRevisionRepository revisionRepository) {
        this.itemRepository = itemRepository;
        this.revisionRepository = revisionRepository;
    }

    @Override
    public boolean isItemActive(UUID itemId) {
        return itemRepository
                .findById(itemId)
                .map(item -> !item.isArchived() && item.getAccessRevokedAt() == null)
                .orElse(false);
    }

    @Override
    public long countTotalContentItems() {
        return itemRepository.countByArchivedFalse();
    }

    @Override
    public long countPendingReviewRevisions() {
        return revisionRepository.countByStatus("IN_REVIEW");
    }
}
