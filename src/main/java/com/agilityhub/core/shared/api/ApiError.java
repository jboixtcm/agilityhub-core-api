package com.agilityhub.core.shared.api;

import java.util.Map;

public record ApiError(String code, String message, Map<String, Object> details, String traceId) { }
