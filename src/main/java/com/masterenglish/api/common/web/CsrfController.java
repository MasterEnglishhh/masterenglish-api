package com.masterenglish.api.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    @GetMapping("/api/v1/auth/csrf")
    public ResponseEntity<CsrfResponse> csrf(HttpServletRequest request) {
        Object attribute = request.getAttribute(DeferredCsrfToken.class.getName());
        if (!(attribute instanceof DeferredCsrfToken deferred)) {
            throw new IllegalStateException("CSRF protection is not configured for this endpoint.");
        }
        // The SPA header needs the raw cookie token, not the XOR-masked request attribute.
        CsrfToken token = deferred.get();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    public record CsrfResponse(String headerName, String token) {}
}
