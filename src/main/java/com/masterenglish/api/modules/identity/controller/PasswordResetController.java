package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.modules.identity.dto.AuthMessageResponse;
import com.masterenglish.api.modules.identity.dto.ForgotPasswordRequest;
import com.masterenglish.api.modules.identity.dto.PasswordResetChallengeResponse;
import com.masterenglish.api.modules.identity.dto.PasswordResetGrantResponse;
import com.masterenglish.api.modules.identity.dto.PasswordResetOtpRequest;
import com.masterenglish.api.modules.identity.dto.ResetPasswordRequest;
import com.masterenglish.api.modules.identity.security.AuthCookieHelper;
import com.masterenglish.api.modules.identity.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password")
public class PasswordResetController {
    private final PasswordResetService service;
    private final AuthCookieHelper cookies;

    public PasswordResetController(PasswordResetService service, AuthCookieHelper cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping("/forgot")
    public ResponseEntity<PasswordResetChallengeResponse> request(
            @Valid @RequestBody ForgotPasswordRequest input, HttpServletRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(service.request(input.email(), request.getRemoteAddr()));
    }

    @PostMapping("/verify")
    public ResponseEntity<PasswordResetGrantResponse> verify(
            @Valid @RequestBody PasswordResetOtpRequest input, HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.verify(input, request.getRemoteAddr()));
    }

    @PostMapping("/reset")
    public ResponseEntity<AuthMessageResponse> reset(
            @Valid @RequestBody ResetPasswordRequest input) {
        service.reset(input);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clearAccessTokenCookie().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearRefreshTokenCookie().toString())
                .body(
                        new AuthMessageResponse(
                                "Mật khẩu đã được cập nhật. Vui lòng đăng nhập lại."));
    }
}
