package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.service.ContentQueryService;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ContentQueryServiceImpl implements ContentQueryService {

    private final ContentItemRepository itemRepository;
    private final ContentRevisionRepository revisionRepository;

    public ContentQueryServiceImpl(
            ContentItemRepository itemRepository, ContentRevisionRepository revisionRepository) {
        this.itemRepository = itemRepository;
        this.revisionRepository = revisionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isItemActive(UUID itemId) {
        return itemRepository
                .findById(itemId)
                .map(item -> !item.isArchived() && item.getAccessRevokedAt() == null)
                .orElse(false);
    }

    @Override
    @Transactional
    public Optional<String> snapshot(UUID itemId, UUID revisionId, String type, boolean lock) {
        log.debug("Reading content snapshot {} with locking={}", revisionId, lock);
        return revisionRepository.snapshot(itemId, revisionId, type, lock);
    }
}
