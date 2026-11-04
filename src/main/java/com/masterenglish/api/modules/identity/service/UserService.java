package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.request.UpdateProfileRequest;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import java.util.UUID;

public interface UserService {

    UserProfileResponse findProfileById(UUID id);

    UserProfileResponse updateProfile(UUID id, UpdateProfileRequest request);
}
