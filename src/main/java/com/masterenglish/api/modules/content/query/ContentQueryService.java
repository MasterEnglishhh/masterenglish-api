package com.masterenglish.api.modules.content.query;

import java.util.UUID;

public interface ContentQueryService {
    boolean isItemActive(UUID itemId);
}
