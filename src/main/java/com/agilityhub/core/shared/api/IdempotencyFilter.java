package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Registered after Spring Security: scope comes only from a validated JWT. */
public class IdempotencyFilter extends OncePerRequestFilter {
    private final IdempotencyRepository records;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ApiExceptionHandler errors;
    private final ObjectMapper mapper;

    public IdempotencyFilter(IdempotencyRepository records, TransactionTemplate transactions, Clock clock,
                             ApiExceptionHandler errors, ObjectMapper mapper) {
        this.records = records;
        this.transactions = transactions;
        this.clock = clock;
        this.errors = errors;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || request.getHeader("Idempotency-Key") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            execute(request, response, chain);
        } catch (ApiException exception) {
            response.setStatus(exception.code().httpStatus());
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), errors.body(exception, request));
        }
    }

    private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        String clubId = jwt.getToken().getClaimAsString("clubId");
        String accountId = jwt.getToken().getSubject();
        if (clubId == null || clubId.isBlank() || accountId == null || accountId.isBlank()) {
            throw new ApiException(ErrorCode.NO_MEMBERSHIP);
        }
        String key = request.getHeader("Idempotency-Key");
        try {
            if (!UUID.fromString(key).toString().equalsIgnoreCase(key)) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "Idempotency-Key"));
        }
        byte[] body = request.getInputStream().readAllBytes();
        String hash = hash(request, body);
        var claim = records.claim(clubId, accountId, key, hash, clock.instant());
        var record = claim.record();
        if (!claim.acquired()) {
            if (!record.requestHash().equals(hash)) {
                throw new ApiException(ErrorCode.STALE_VERSION, Map.of("reason", "IDEMPOTENCY_KEY_REUSED"));
            }
            if (record.status() == com.agilityhub.core.shared.persistence.IdempotencyRecord.Status.IN_PROGRESS) {
                throw new ApiException(ErrorCode.STALE_VERSION, Map.of("reason", "IN_PROGRESS"));
            }
            response.setStatus(record.responseStatus());
            record.responseHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
            response.getOutputStream().write(record.responseBody());
            return;
        }
        var cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            transactions.executeWithoutResult(status -> {
                records.lock(record);
                try { chain.doFilter(new BufferedRequest(request, body), cachedResponse); }
                catch (IOException | ServletException failure) { throw new RequestFailure(failure); }
                if (request.isAsyncStarted()) { throw new IllegalStateException("Idempotent POST must be synchronous"); }
                Map<String, List<String>> headers = new LinkedHashMap<>();
                for (String header : List.of("Content-Type", "Location", "ETag", "Cache-Control", "Content-Language")) {
                    headers.put(header, List.copyOf(cachedResponse.getHeaders(header)));
                }
                records.complete(record, cachedResponse.getStatus(), cachedResponse.getContentAsByteArray(), headers);
            });
        } catch (RuntimeException failure) {
            records.abandon(record);
            if (failure instanceof RequestFailure wrapped) {
                if (wrapped.getCause() instanceof IOException io) { throw io; }
                throw (ServletException) wrapped.getCause();
            }
            throw failure;
        }
        cachedResponse.copyBodyToResponse();
    }

    private String hash(HttpServletRequest request, byte[] body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String target = request.getMethod() + "\n" + request.getRequestURI() + "\n"
                    + request.getQueryString() + "\n" + request.getContentType() + "\n";
            digest.update(target.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class RequestFailure extends RuntimeException {
        private RequestFailure(Exception cause) { super(cause); }
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous requests only");
                }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
