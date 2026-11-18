package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.request.RegisterRequest;
import com.masterenglish.api.modules.identity.dto.request.RegistrationOtpRequest;
import com.masterenglish.api.modules.identity.dto.request.ResendRegistrationOtpRequest;
import com.masterenglish.api.modules.identity.dto.response.RegistrationChallengeResponse;

public interface RegistrationService {
    RegistrationChallengeResponse register(RegisterRequest input, String clientIp);

    RegistrationChallengeResponse resend(ResendRegistrationOtpRequest input, String clientIp);

    void verify(RegistrationOtpRequest input, String clientIp);
}
