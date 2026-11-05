package com.masterenglish.api.modules.identity.user;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserLockServiceImpl implements UserLockService {

    private final UserRepository userRepository;

    public UserLockServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void verifyAndLockUser(UUID userId) {
        userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    @Override
    public void verifyContentAdmin(UUID userId) {
        var user =
                userRepository
                        .findByIdForUpdate(userId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.ACCESS_DENIED,
                                                "Content administrator required."));
        if (!user.getStatus().name().equals("ACTIVE")
                || !user.getRoleNames().contains("CONTENT_ADMIN")) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED, "Active content administrator required.");
        }
    }
}
