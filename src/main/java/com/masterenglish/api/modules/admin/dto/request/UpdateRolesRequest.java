package com.masterenglish.api.modules.admin.dto.request;

import com.masterenglish.api.common.security.RoleCode;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record UpdateRolesRequest(@NotEmpty List<RoleCode> roles) {}
