package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.modules.identity.dto.request.RegisterRequest;
import com.masterenglish.api.modules.identity.dto.request.RegistrationOtpRequest;
import com.masterenglish.api.modules.identity.dto.request.ResendRegistrationOtpRequest;
import com.masterenglish.api.modules.identity.dto.response.AuthMessageResponse;
import com.masterenglish.api.modules.identity.dto.response.RegistrationChallengeResponse;
import com.masterenglish.api.modules.identity.security.ClientIpResolver;
import com.masterenglish.api.modules.identity.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/register")
public class RegistrationController {
    private final RegistrationService service;
    private final ClientIpResolver clientIpResolver;

    public RegistrationController(RegistrationService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping
    public ResponseEntity<RegistrationChallengeResponse> register(
            @Valid @RequestBody RegisterRequest input, HttpServletRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(service.register(input, clientIpResolver.resolve(request)));
    }

    @PostMapping("/resend")
    public ResponseEntity<RegistrationChallengeResponse> resend(
            @Valid @RequestBody ResendRegistrationOtpRequest input, HttpServletRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(service.resend(input, clientIpResolver.resolve(request)));
    }

    @PostMapping("/verify")
    public ResponseEntity<AuthMessageResponse> verify(
            @Valid @RequestBody RegistrationOtpRequest input, HttpServletRequest request) {
        service.verify(input, clientIpResolver.resolve(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new AuthMessageResponse(
                                "Tài khoản đã được xác minh. Vui lòng đăng nhập để bắt đầu."));
    }
}
