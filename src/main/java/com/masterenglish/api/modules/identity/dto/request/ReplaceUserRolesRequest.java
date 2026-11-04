package com.masterenglish.api.modules.identity.dto.request;

import com.masterenglish.api.common.security.RoleCode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record ReplaceUserRolesRequest(@NotEmpty Set<@NotNull RoleCode> roles) {}
