package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.error.BusinessException;
import org.junit.jupiter.api.Test;

class RegistrationPasswordPolicyTests {
    private final RegistrationPasswordPolicy policy = new RegistrationPasswordPolicy();

    @Test
    void validatesUnicodeCodePointsAndUtf8BytesWithoutCompositionRules() {
        assertThat(policy.normalizeAndValidate("a".repeat(15))).isEqualTo("a".repeat(15));
        assertThat(policy.normalizeAndValidate("🌿".repeat(18))).isEqualTo("🌿".repeat(18));
        assertThatThrownBy(() -> policy.normalizeAndValidate("🌿".repeat(19)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.normalizeAndValidate("🌿".repeat(14)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.normalizeAndValidate("a".repeat(73)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void normalizesNfcAndPreservesSpacesAndCase() {
        assertThat(policy.normalizeAndValidate("  Ca phe\u0301 và một ngày mới  "))
                .isEqualTo("  Ca phé và một ngày mới  ");
    }

    @Test
    void rejectsCommonPasswords() {
        assertThatThrownBy(() -> policy.normalizeAndValidate("password123456789"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void breachRangeIgnoresPaddingAndMatchesCaseInsensitively() {
        assertThat(BreachedPasswordChecker.matchesRange("ABCDEF", "ABCDEF:0\r\nOTHER:30"))
                .isFalse();
        assertThat(BreachedPasswordChecker.matchesRange("ABCDEF", "abcdef:20\r\nOTHER:0")).isTrue();
    }
}
