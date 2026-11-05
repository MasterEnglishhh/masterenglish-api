package com.masterenglish.api.modules.learning.dto;

import com.masterenglish.api.modules.vocabulary.query.WordSenseCardDto;
import java.util.List;

public record DueQueueResponse(long totalDue, List<WordSenseCardDto> cards) {}
