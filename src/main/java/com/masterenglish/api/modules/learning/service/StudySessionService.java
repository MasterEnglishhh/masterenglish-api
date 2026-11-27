package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.modules.learning.dto.request.CloseStudySessionRequest;
import com.masterenglish.api.modules.learning.dto.request.StartStudySessionRequest;
import com.masterenglish.api.modules.learning.dto.request.StudyHeartbeatRequest;
import com.masterenglish.api.modules.learning.dto.response.StudySessionResponse;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public interface StudySessionService {

    StudySessionResponse startSession(UUID userId, StartStudySessionRequest request);

    StudySessionResponse heartbeat(UUID userId, UUID sessionId, StudyHeartbeatRequest request);

    StudySessionResponse closeSession(
            UUID userId, UUID sessionId, CloseStudySessionRequest request);

    Map<UUID, Long> sumDailyStudySeconds(Map<UUID, LocalDate> userLocalDays);

    long getDailyStudySeconds(UUID userId, LocalDate localDay);

    java.util.Set<LocalDate> findActivityLocalDays(UUID userId);
}
