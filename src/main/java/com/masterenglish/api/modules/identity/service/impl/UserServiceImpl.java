package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.request.UpdateProfileRequest;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.UserService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository =
                Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    @Override
    public UserProfileResponse findProfileById(UUID id) {
        return UserProfileResponse.from(requireUser(id));
    }

    @Override
    @Transactional
    public UserProfileResponse updateProfile(UUID id, UpdateProfileRequest request) {
        User user = requireUser(id);
        user.updateProfile(
                request.firstName(), request.lastName(), request.phone(), user.getAvatarMediaId());
        return UserProfileResponse.from(user);
    }

    private User requireUser(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return userRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
    }
}
