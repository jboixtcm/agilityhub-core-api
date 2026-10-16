package com.agilityhub.core.shared.domain;

import java.util.Map;
import java.util.Objects;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code) {
        this(code, Map.of());
    }

    /**
     * `details` is copied in its own order, and a null value is kept: the contract sends `null` where a detail has no value (E10-T03:
     * `HOST_NOT_VERIFIED`'s TXT pair for a domain without a challenge, which `Map.copyOf` turned into a 500).
     */
    public ApiException(ErrorCode code, Map<String, Object> details) {
        super(code.name());
        this.code = Objects.requireNonNull(code);
        this.details = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
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
