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

    /**
     * The contract's answer to a failure whose cause must stay visible, for example a Mongo write conflict met inside a caller's
     * transaction: the caller answers `code`, while a retry loop that inspects the cause chain still sees the conflict (E5-T27).
     */
    public ApiException(ErrorCode code, Throwable cause) {
        super(code.name(), cause);
        this.code = Objects.requireNonNull(code);
        this.details = Map.of();
    }

    public ErrorCode code() { return code; }
    public Map<String, Object> details() { return details; }
}
