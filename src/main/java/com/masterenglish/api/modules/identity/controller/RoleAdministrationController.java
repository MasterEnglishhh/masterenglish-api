package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.common.error.RequestIdFilter;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.identity.dto.request.ReplaceUserRolesRequest;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.security.ClientIpResolver;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import com.masterenglish.api.modules.identity.service.RoleAdministrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class RoleAdministrationController {

    private final RoleAdministrationService service;
    private final ClientIpResolver clientIp;

    public RoleAdministrationController(
            RoleAdministrationService service, ClientIpResolver clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    @PutMapping("/{userId}/roles")
    public ResponseEntity<UserProfileResponse> replaceRoles(
            @CurrentUser UserPrincipal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody ReplaceUserRolesRequest input,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        service.replaceRoles(
                                principal,
                                userId,
                                input.roles(),
                                requestId,
                                clientIp.resolve(request)));
    }
}
