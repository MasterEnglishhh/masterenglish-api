package com.masterenglish.api.modules.content.query;

import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContentQueryServiceImpl implements ContentQueryService {

    private final ContentItemRepository itemRepository;

    public ContentQueryServiceImpl(ContentItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @Override
    public boolean isItemActive(UUID itemId) {
        return itemRepository
                .findById(itemId)
                .map(item -> !item.isArchived() && item.getAccessRevokedAt() == null)
                .orElse(false);
    }
}
