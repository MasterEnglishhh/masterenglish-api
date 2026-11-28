package com.masterenglish.api.modules.practice.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.modules.practice.dto.response.MistakeResponse;
import com.masterenglish.api.modules.practice.entity.MistakeRecord;
import com.masterenglish.api.modules.practice.repository.MistakeRecordRepository;
import com.masterenglish.api.modules.practice.service.MistakeService;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MistakeServiceImpl implements MistakeService {

    private static final Set<String> STATES =
            Set.of(MistakeRecord.OPEN, MistakeRecord.IMPROVING, MistakeRecord.RESOLVED);

    /** Placeholder for "no topic filter"; it is never matched because the flag is set. */
    private static final UUID NO_TOPIC = new UUID(0L, 0L);

    private final MistakeRecordRepository mistakes;

    public MistakeServiceImpl(MistakeRecordRepository mistakes) {
        this.mistakes = mistakes;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MistakeResponse> list(
            UUID userId, String state, UUID topicId, Integer page, Integer size) {
        boolean anyState = state == null || state.isBlank();
        if (!anyState && !STATES.contains(state.trim())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "state must be OPEN, IMPROVING or RESOLVED.");
        }
        Pageable base = PageRequestUtils.of(page, size);
        Pageable sorted =
                PageRequest.of(
                        base.getPageNumber(),
                        base.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "lastWrongAt")
                                .and(Sort.by(Sort.Direction.DESC, "id")));
        Page<MistakeRecord> result =
                mistakes.search(
                        userId,
                        anyState,
                        anyState ? "" : state.trim(),
                        topicId == null,
                        topicId == null ? NO_TOPIC : topicId,
                        sorted);
        return PageResponse.from(result, MistakeServiceImpl::toResponse);
    }

    private static MistakeResponse toResponse(MistakeRecord m) {
        return new MistakeResponse(
                m.getId(),
                m.getQuestionId(),
                m.getState(),
                m.getWrongCount(),
                m.getConsecutiveCorrect(),
                m.getLastWrongAt(),
                m.getLastEvaluatedAt(),
                m.getResolvedAt(),
                m.getSourceAttemptId());
    }
}
