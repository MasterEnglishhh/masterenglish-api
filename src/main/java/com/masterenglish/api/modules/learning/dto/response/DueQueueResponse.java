package com.masterenglish.api.modules.learning.dto.response;

import com.masterenglish.api.common.api.WordSenseCardDto;
import java.util.List;

public record DueQueueResponse(long totalDue, List<WordSenseCardDto> cards) {}
