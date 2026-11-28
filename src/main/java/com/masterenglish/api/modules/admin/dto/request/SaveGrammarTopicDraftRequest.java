package com.masterenglish.api.modules.admin.dto.request;

import java.util.UUID;

/** Mọi trường đều tùy chọn: bản nháp chỉ cần đủ khi xuất bản, validator grammar kiểm tra lúc đó. */
public record SaveGrammarTopicDraftRequest(
        String slug,
        String name,
        String description,
        String difficulty,
        Integer sortOrder,
        UUID sourceId,
        String changeSummary) {}
