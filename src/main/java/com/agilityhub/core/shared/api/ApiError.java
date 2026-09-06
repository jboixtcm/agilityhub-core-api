package com.agilityhub.core.shared.api;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"code", "message", "details", "traceId"})
public record ApiError(String code, String message,
                       @Schema(additionalProperties = Schema.AdditionalPropertiesValue.TRUE) Map<String, Object> details,
                       String traceId) { }
