package com.masterenglish.api.modules.admin.dto.response;

import com.masterenglish.api.common.error.FieldViolation;
import java.util.List;

public record AdminValidationResponse(boolean valid, List<FieldViolation> errors) {}
