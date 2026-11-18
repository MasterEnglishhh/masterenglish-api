package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.UserProfileMapper;
import com.masterenglish.api.modules.identity.service.UserService;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository =
                Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse findProfileById(UUID id) {
        log.debug("Reading user profile");
        Objects.requireNonNull(id, "id must not be null");
        return userRepository
                .findById(id)
                .map(UserProfileMapper::from)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
    }
}
