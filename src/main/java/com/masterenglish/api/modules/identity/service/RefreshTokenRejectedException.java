package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;

public final class RefreshTokenRejectedException extends BusinessException {

    private final boolean alreadyRotated;

    public RefreshTokenRejectedException(boolean alreadyRotated) {
        super(ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        this.alreadyRotated = alreadyRotated;
    }

    public boolean alreadyRotated() {
        return alreadyRotated;
    }
}
