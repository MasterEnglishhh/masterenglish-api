package com.masterenglish.api.modules.content.service;

import java.util.Optional;
import java.util.UUID;

public interface ContentQueryService {
    boolean isItemActive(UUID itemId);

    Optional<String> snapshot(UUID itemId, UUID revisionId, String type, boolean lock);
}
