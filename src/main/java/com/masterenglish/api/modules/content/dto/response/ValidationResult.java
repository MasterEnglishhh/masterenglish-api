package com.masterenglish.api.modules.content.dto.response;

import com.masterenglish.api.common.error.FieldViolation;
import java.util.List;

public record ValidationResult(boolean valid, List<FieldViolation> errors) {}
