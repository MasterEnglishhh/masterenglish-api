package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.identity.dto.response.AuthSessionResponse;
import com.masterenglish.api.modules.identity.security.AuthCookieHelper;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import com.masterenglish.api.modules.identity.service.AuthSessionService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/sessions")
public class AuthSessionController {

    private final AuthSessionService sessions;
    private final AuthCookieHelper cookies;

    public AuthSessionController(AuthSessionService sessions, AuthCookieHelper cookies) {
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @GetMapping
    public ResponseEntity<List<AuthSessionResponse>> list(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessions.listSessions(principal.getId(), principal.getSessionId()));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(
            @CurrentUser UserPrincipal principal, @PathVariable UUID sessionId) {
        sessions.revokeOwnedSession(principal.getId(), sessionId);
        var response = ResponseEntity.noContent();
        if (sessionId.equals(principal.getSessionId())) {
            response.header(HttpHeaders.SET_COOKIE, cookies.clearAccessTokenCookie().toString());
            response.header(HttpHeaders.SET_COOKIE, cookies.clearRefreshTokenCookie().toString());
        }
        return response.build();
    }
}
