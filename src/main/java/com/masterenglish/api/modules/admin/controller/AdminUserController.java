package com.masterenglish.api.modules.admin.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.admin.dto.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.user.LockUserRequest;
import com.masterenglish.api.modules.admin.dto.user.UpdateRolesRequest;
import com.masterenglish.api.modules.admin.service.AdminUserService;
import com.masterenglish.api.modules.identity.admin.AdminUserDto;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminUserController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "email", "id");

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
    }

    @GetMapping
    public ResponseEntity<PageResponse<AdminUserDto>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RoleCode role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection) {
        Pageable pageable = PageRequestUtils.of(page, size, sortBy, sortDirection, ALLOWED_SORT_FIELDS);
        return ResponseEntity.ok(userService.findUsers(search, role, status, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDto> getUserDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserDetail(id));
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<AdminUserDto> updateRoles(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRolesRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(userService.updateUserRoles(id, request, currentUser.getId()));
    }

    @PostMapping("/{id}/lock")
    public ResponseEntity<AdminUserDto> lockUser(
            @PathVariable UUID id,
            @Valid @RequestBody LockUserRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(userService.lockUser(id, request, currentUser.getId()));
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<AdminUserDto> unlockUser(
            @PathVariable UUID id,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(userService.unlockUser(id, currentUser.getId()));
    }

    @PostMapping("/{id}/revoke-sessions")
    public ResponseEntity<AdminMessageResponse> revokeSessions(
            @PathVariable UUID id,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(userService.revokeSessions(id, currentUser.getId()));
    }
}
