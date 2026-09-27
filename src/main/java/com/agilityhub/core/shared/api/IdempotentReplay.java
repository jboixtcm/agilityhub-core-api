package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.domain.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * CONVENCIONS_API §7 (ruling E62): what a repeated `Idempotency-Key` answers — the stored response, or
 * `IDEMPOTENCY_KEY_REUSED` (another request, or the first one still running). {@link IdempotencyFilter} only leaves it on
 * the request; {@link IdempotentReplayAspect} answers it in place of the handler method, so the route's own authorization
 * (the MVC interceptors with the impersonation and module guards, then `@PreAuthorize`) has accepted the current token
 * first. When it refuses, the request gets what that authorization answers and nothing of the stored response.
 */
public final class IdempotentReplay extends RuntimeException {
    static final String ATTRIBUTE = IdempotentReplay.class.getName();
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final transient ApiException refusal;

    private IdempotentReplay(int status, Map<String, List<String>> headers, byte[] body, ApiException refusal) {
        super("Idempotent replay", null, false, false);
        this.status = status; this.headers = headers; this.body = body; this.refusal = refusal;
    }

    static IdempotentReplay stored(int status, Map<String, List<String>> headers, byte[] body) { return new IdempotentReplay(status, headers, body, null); }
    static IdempotentReplay refused(ApiException refusal) { return new IdempotentReplay(0, Map.of(), new byte[0], refusal); }

    void pending(HttpServletRequest request) { request.setAttribute(ATTRIBUTE, this); }

    /** The pending answer of the current request, taken once (null outside a replay). */
    static IdempotentReplay take() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null || !(attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof IdempotentReplay replay)) { return null; }
        attributes.removeAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return replay;
    }

    /** Thrown by the aspect: the stored response itself, or the `IDEMPOTENCY_KEY_REUSED` refusal. */
    RuntimeException answer() { return refusal != null ? refusal : this; }

    void writeTo(HttpServletResponse response) throws IOException {
        response.setStatus(status);
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                response.setHeader(name, values.getFirst());
                values.stream().skip(1).forEach(value -> response.addHeader(name, value));
            }
        });
        response.getOutputStream().write(body);
    }
}
