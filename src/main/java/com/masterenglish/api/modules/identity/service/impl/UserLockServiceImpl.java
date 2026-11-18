package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.UserLockService;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class UserLockServiceImpl implements UserLockService {

    private final UserRepository userRepository;

    public UserLockServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public UUID resolveContentAdmin(String email) {
        log.debug("Resolving content administrator for import");
        UUID id =
                userRepository
                        .findIdByEmailIgnoreCase(email.strip())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.ACCESS_DENIED,
                                                "Content administrator account not found."));
        verifyContentAdmin(id);
        return id;
    }

    @Override
    @Transactional
    public void verifyAndLockUser(UUID userId) {
        userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    @Override
    @Transactional
    public void verifyContentAdmin(UUID userId) {
        verifyContentRole(userId, false);
    }

    @Override
    @Transactional
    public void verifyContentAuthor(UUID userId) {
        verifyContentRole(userId, true);
    }

    private void verifyContentRole(UUID userId, boolean allowSystemAdmin) {
        var user =
                userRepository
                        .findByIdForUpdate(userId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.ACCESS_DENIED,
                                                "Content administrator required."));
        var roles = user.getRoleNames();
        if (!user.getStatus().name().equals("ACTIVE")
                || !(roles.contains("CONTENT_ADMIN")
                        || (allowSystemAdmin && roles.contains("SYSTEM_ADMIN")))) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED, "Active content administrator required.");
        }
    }
}
