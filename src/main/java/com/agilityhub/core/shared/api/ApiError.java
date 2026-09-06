package com.agilityhub.core.shared.api;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

public record ApiError(String code, String message,
                       @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                               additionalProperties = Schema.AdditionalPropertiesValue.TRUE) Map<String, Object> details,
                       String traceId) { }
