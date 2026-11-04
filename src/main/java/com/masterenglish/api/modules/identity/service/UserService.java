package com.masterenglish.api.modules.identity.service;

import java.util.UUID;

import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;

public interface UserService {

    UserProfileResponse findProfileById(UUID id);
}
