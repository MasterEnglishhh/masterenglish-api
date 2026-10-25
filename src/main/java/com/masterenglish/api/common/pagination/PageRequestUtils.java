package com.masterenglish.api.common.pagination;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

public final class PageRequestUtils {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final String DEFAULT_SORT_BY = "createdAt";

    private PageRequestUtils() {
    }

    public static Pageable of(Integer page, Integer size) {
        return PageRequest.of(validPage(page), validSize(size));
    }

    /** Callers must supply an endpoint-specific allowlist of sortable fields. */
    public static Pageable of(Integer page, Integer size, String sortBy, String sortDirection,
            Set<String> allowedSortFields) {
        String field = sortBy == null || sortBy.isBlank() ? DEFAULT_SORT_BY : sortBy.trim();
        if (!allowedSortFields.contains(field)) {
            throw invalid("Unsupported sort field.");
        }
        String direction = sortDirection == null || sortDirection.isBlank() ? "desc" : sortDirection.trim();
        if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
            throw invalid("Sort direction must be asc or desc.");
        }
        Sort.Direction order = Sort.Direction.fromString(direction);
        Sort sort = Sort.by(order, field);
        if (!"id".equals(field) && allowedSortFields.contains("id")) {
            sort = sort.and(Sort.by(order, "id"));
        }
        return PageRequest.of(validPage(page), validSize(size), sort);
    }

    private static int validPage(Integer page) {
        if (page != null && page < 0) {
            throw invalid("Page must be zero or greater.");
        }
        return page == null ? DEFAULT_PAGE : page;
    }

    private static int validSize(Integer size) {
        if (size != null && (size < 1 || size > MAX_SIZE)) {
            throw invalid("Size must be between 1 and 100.");
        }
        return size == null ? DEFAULT_SIZE : size;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
