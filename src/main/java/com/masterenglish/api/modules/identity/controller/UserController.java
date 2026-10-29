package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.identity.dto.UserProfileResponse;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import com.masterenglish.api.modules.identity.service.UserService;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(userService.findProfileById(principal.getId()));
    }
}
