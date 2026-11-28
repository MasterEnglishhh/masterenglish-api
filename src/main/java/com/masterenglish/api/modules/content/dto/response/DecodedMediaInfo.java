package com.masterenglish.api.modules.content.dto.response;

public record DecodedMediaInfo(
        String mimeType,
        String codec,
        Integer width,
        Integer height,
        Double durationSeconds,
        Integer sampleRate,
        Integer channels) {}
