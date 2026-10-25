package com.masterenglish.api.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.error.BusinessException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PageRequestUtilsTests {

    private static final Set<String> ALLOWED = Set.of("id", "createdAt", "displayName");

    @Test
    void rejectsSortingByAFieldNotExposedByTheEndpoint() {
        assertThatThrownBy(() -> PageRequestUtils.of(0, 20, "passwordHash", "asc", ALLOWED))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsRequestsOutsideThePageSizeBudget() {
        assertThatThrownBy(() -> PageRequestUtils.of(0, 101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PageRequestUtils.of(-1, 20)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsInvalidSortDirections() {
        assertThatThrownBy(() -> PageRequestUtils.of(0, 20, "createdAt", "sideways", ALLOWED))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void usesStableOrderingForEqualSortValues() {
        var pageable = PageRequestUtils.of(null, null, "displayName", "asc", ALLOWED);
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().stream().map(order -> order.getProperty()).toList())
                .containsExactly("displayName", "id");
    }
}
