package com.agilityhub.core.shared.domain;

import java.util.Map;
import java.util.Objects;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code) {
        this(code, Map.of());
    }

    public ApiException(ErrorCode code, Map<String, Object> details) {
        super(code.name());
        this.code = Objects.requireNonNull(code);
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() { return code; }
    public Map<String, Object> details() { return details; }
}
