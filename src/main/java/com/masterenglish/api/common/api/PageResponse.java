package com.masterenglish.api.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public record PageResponse<T>(
        List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {

    public PageResponse {
        items = List.copyOf(items);
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    public static <T, R> PageResponse<R> from(Page<T> page, Function<T, R> mapper) {
        return from(page.map(mapper));
    }
}
