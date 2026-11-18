package com.masterenglish.api.modules.learning.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.UUID;

public record SavedSenseIdsResponse(List<UUID> senseIds) {
    @JsonValue
    public List<UUID> senseIds() {
        return senseIds;
    }
}
