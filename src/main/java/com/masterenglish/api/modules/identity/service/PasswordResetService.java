package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.request.PasswordResetOtpRequest;
import com.masterenglish.api.modules.identity.dto.request.ResetPasswordRequest;
import com.masterenglish.api.modules.identity.dto.response.PasswordResetChallengeResponse;
import com.masterenglish.api.modules.identity.dto.response.PasswordResetGrantResponse;

public interface PasswordResetService {
    PasswordResetChallengeResponse request(String rawEmail, String clientIp);

    PasswordResetGrantResponse verify(PasswordResetOtpRequest input, String clientIp);

    void reset(ResetPasswordRequest input);
}
