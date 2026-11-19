package com.masterenglish.api.modules.notifications.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.notifications.dto.request.UpdateNotificationPreferencesRequest;
import com.masterenglish.api.modules.notifications.dto.response.NotificationPreferencesResponse;
import com.masterenglish.api.modules.notifications.dto.response.NotificationResponse;
import com.masterenglish.api.modules.notifications.dto.response.UnreadCountResponse;
import com.masterenglish.api.modules.notifications.service.NotificationService;
import jakarta.validation.Valid;
import java.util.UUID;
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
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasRole('LEARNER')")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public PageResponse<NotificationResponse> getFeed(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return notificationService.getFeed(currentUser.getId(), PageRequestUtils.of(page, size));
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse countUnread(@CurrentUser AuthenticatedUser currentUser) {
        return notificationService.countUnread(currentUser.getId());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID id) {
        notificationService.markRead(currentUser.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@CurrentUser AuthenticatedUser currentUser) {
        notificationService.markAllRead(currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public NotificationPreferencesResponse getPreferences(
            @CurrentUser AuthenticatedUser currentUser) {
        return notificationService.getPreferences(currentUser.getId());
    }

    @PutMapping("/preferences")
    public NotificationPreferencesResponse updatePreferences(
            @CurrentUser AuthenticatedUser currentUser,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return notificationService.updatePreferences(currentUser.getId(), request);
    }
}
