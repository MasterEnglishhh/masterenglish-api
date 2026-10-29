package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.modules.identity.dto.AuthMessageResponse;
import com.masterenglish.api.modules.identity.dto.RegisterRequest;
import com.masterenglish.api.modules.identity.dto.RegistrationChallengeResponse;
import com.masterenglish.api.modules.identity.dto.RegistrationOtpRequest;
import com.masterenglish.api.modules.identity.dto.ResendRegistrationOtpRequest;
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

    public RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RegistrationChallengeResponse> register(
            @Valid @RequestBody RegisterRequest input, HttpServletRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(service.register(input, request.getRemoteAddr()));
    }

    @PostMapping("/resend")
    public ResponseEntity<RegistrationChallengeResponse> resend(
            @Valid @RequestBody ResendRegistrationOtpRequest input, HttpServletRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(service.resend(input, request.getRemoteAddr()));
    }

    @PostMapping("/verify")
    public ResponseEntity<AuthMessageResponse> verify(
            @Valid @RequestBody RegistrationOtpRequest input, HttpServletRequest request) {
        service.verify(input, request.getRemoteAddr());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new AuthMessageResponse(
                                "Tài khoản đã được xác minh. Vui lòng đăng nhập để bắt đầu."));
    }
}
