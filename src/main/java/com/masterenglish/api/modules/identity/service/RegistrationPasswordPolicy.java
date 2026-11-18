package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RegistrationPasswordPolicy {
    private static final Set<String> COMMON =
            Set.of(
                    "123456789012345",
                    "1234567890123456",
                    "password123456789",
                    "passwordpassword",
                    "qwertyuiopasdfgh",
                    "letmeinletmeinletmein");

    public String normalizeAndValidate(String password) {
        String normalized = Normalizer.normalize(password, Normalizer.Form.NFC);
        if (normalized.isBlank()
                || normalized.codePointCount(0, normalized.length()) < 15
                || normalized.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(
                    ErrorCode.PASSWORD_REJECTED,
                    "Mật khẩu cần ít nhất 15 ký tự và tối đa 72 byte UTF-8; dấu cách được giữ nguyên.");
        }
        if (COMMON.contains(normalized.toLowerCase(Locale.ROOT))) {
            log.debug("Registration password rejected by the common password policy");
            throw new BusinessException(
                    ErrorCode.PASSWORD_REJECTED,
                    "Mật khẩu này quá phổ biến. Vui lòng chọn mật khẩu khác.");
        }
        return normalized;
    }
}
