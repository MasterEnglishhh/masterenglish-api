package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.infrastructure.security.AuthCookieHelper;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.dto.request.ChangePasswordRequest;
import com.masterenglish.api.modules.identity.dto.request.UpdateLearningGoalRequest;
import com.masterenglish.api.modules.identity.dto.request.UpdateProfileRequest;
import com.masterenglish.api.modules.identity.dto.response.LearningGoalResponse;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.service.UserService;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final AuthCookieHelper authCookieHelper;

    public UserController(UserService userService, AuthCookieHelper authCookieHelper) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.authCookieHelper =
                Objects.requireNonNull(authCookieHelper, "authCookieHelper must not be null");
    }

    @PutMapping("/me")
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(principal.getId(), request));
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changeMyPassword(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.getId(), request);
        // Every session is revoked, so clear the cookies and make the client sign in again.
        return ResponseEntity.noContent()
                .header(
                        HttpHeaders.SET_COOKIE,
                        authCookieHelper.clearAccessTokenCookie().toString())
                .header(
                        HttpHeaders.SET_COOKIE,
                        authCookieHelper.clearRefreshTokenCookie().toString())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(userService.findProfileById(principal.getId()));
    }

    @GetMapping("/me/goal")
    public ResponseEntity<LearningGoalResponse> getMyGoal(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(userService.findGoalByUserId(principal.getId()));
    }

    @PutMapping("/me/goal")
    public ResponseEntity<LearningGoalResponse> updateMyGoal(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateLearningGoalRequest request) {
        return ResponseEntity.ok(userService.updateGoal(principal.getId(), request));
    }
}
