package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.identity.dto.request.ChangePasswordRequest;
import com.masterenglish.api.modules.identity.dto.request.PasswordChangeOtpRequest;
import com.masterenglish.api.modules.identity.dto.request.PasswordChangeResendRequest;
import com.masterenglish.api.modules.identity.dto.response.AuthMessageResponse;
import com.masterenglish.api.modules.identity.dto.response.PasswordChangeChallengeResponse;
import com.masterenglish.api.modules.identity.security.AuthCookieHelper;
import com.masterenglish.api.modules.identity.security.ClientIpResolver;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import com.masterenglish.api.modules.identity.service.PasswordChangeService;
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
@RequestMapping("/api/v1/auth/password/change")
public class PasswordChangeController {

    private final PasswordChangeService service;
    private final AuthCookieHelper cookies;
    private final ClientIpResolver clientIp;

    public PasswordChangeController(
            PasswordChangeService service, AuthCookieHelper cookies, ClientIpResolver clientIp) {
        this.service = service;
        this.cookies = cookies;
        this.clientIp = clientIp;
    }

    @PostMapping
    public ResponseEntity<PasswordChangeChallengeResponse> request(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest input,
            HttpServletRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(service.request(principal, input, clientIp.resolve(request)));
    }

    @PostMapping("/resend")
    public ResponseEntity<PasswordChangeChallengeResponse> resend(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody PasswordChangeResendRequest input,
            HttpServletRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(service.resend(principal, input.challengeId(), clientIp.resolve(request)));
    }

    @PostMapping("/verify")
    public ResponseEntity<AuthMessageResponse> confirm(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody PasswordChangeOtpRequest input,
            HttpServletRequest request) {
        service.confirm(principal, input, clientIp.resolve(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clearAccessTokenCookie().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearRefreshTokenCookie().toString())
                .body(
                        new AuthMessageResponse(
                                "Mật khẩu đã được thay đổi. Vui lòng đăng nhập lại."));
    }
}
