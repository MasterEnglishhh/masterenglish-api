package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.UserProfileResponse;
import java.util.UUID;

public interface UserService {

    UserProfileResponse findProfileById(UUID id);
}
