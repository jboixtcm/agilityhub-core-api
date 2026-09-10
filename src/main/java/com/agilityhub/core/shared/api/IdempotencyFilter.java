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

/** Registered after Spring Security: scope comes from a validated JWT or a resolved signup host/capability. */
public class IdempotencyFilter extends OncePerRequestFilter {
    private final IdempotencyRepository records;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ApiExceptionHandler errors;
    private final ObjectMapper mapper;
    private com.agilityhub.core.shared.application.SignupCapabilities capabilities;
    @org.springframework.beans.factory.annotation.Autowired
    public void capabilities(com.agilityhub.core.shared.application.SignupCapabilities capabilities) { this.capabilities = capabilities; }

    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.shared.application.SignupCheckoutGuard signupCheckout;

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
        return HealthRequests.matches(request) || !"POST".equals(request.getMethod()) || (request.getHeader("Idempotency-Key") == null && !request.getRequestURI().equals("/api/v1/signup"));
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
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean anonymous = !(authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated());
        boolean publicSignup = path.equals("/api/v1/signup");
        boolean checkout = path.equals("/api/v1/checkout-sessions");
        if(checkout && signupCheckout!=null) signupCheckout.requireBilling();
        if (anonymous && !publicSignup && !checkout) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        byte[] body = request.getInputStream().readNBytes(publicSignup ? 65537 : 1048577);
        if (body.length > (publicSignup ? 65536 : 1048576)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        if (anonymous && publicSignup) {
            var json = json(body);
            if (json != null && !json.path("website").asText("").isBlank()) {
                org.slf4j.LoggerFactory.getLogger(IdempotencyFilter.class).info("Signup honeypot traceId={} clubId={} ipHash={}",RequestTraceFilter.traceId(request),com.agilityhub.core.shared.application.TenantContext.current(),capabilities.fingerprint(request.getRemoteAddr()));
                response.setStatus(202); return;
            }
        }
        String clubId;
        String accountId;
        if (anonymous) {
            clubId = com.agilityhub.core.shared.application.TenantContext.require();
            String host = java.util.Objects.toString(request.getAttribute("signup.resolvedHost"),request.getServerName()).toLowerCase(java.util.Locale.ROOT);
            accountId = "signup:" + capabilities.fingerprint(host);
            if (checkout) {
                var json = json(body);
                String member = json.path("memberId").asText(null), token = json.path("signupToken").asText(null);
                capabilities.require(member,token);
                accountId += ":" + capabilities.fingerprint(token);
            }
        } else {
            var jwt = (JwtAuthenticationToken) authentication;
            clubId = jwt.getToken().getClaimAsString("clubId"); accountId = jwt.getToken().getSubject();
            if (clubId == null || clubId.isBlank() || accountId == null || accountId.isBlank()) { throw new ApiException(ErrorCode.NO_MEMBERSHIP); }
        }
        String key = request.getHeader("Idempotency-Key");
        try {
            if (!UUID.fromString(key).toString().equalsIgnoreCase(key)) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "Idempotency-Key"));
        }
        String hash = hash(request, body);
        var claim = records.claim(clubId, accountId, key, hash, clock.instant());
        var record = claim.record();
        if (!claim.acquired()) {
            if (!record.requestHash().equals(hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "DIFFERENT_REQUEST"));
            }
            if (record.status() == com.agilityhub.core.shared.persistence.IdempotencyRecord.Status.IN_PROGRESS) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "IN_PROGRESS"));
            }
            response.setStatus(record.responseStatus());
            record.responseHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    response.setHeader(name, values.getFirst());
                    values.stream().skip(1).forEach(value -> response.addHeader(name, value));
                }
            });
            response.getOutputStream().write(anonymous ? capabilities.open(record.responseBody(),record.id()) : record.responseBody());
            return;
        }
        var cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            transactions.executeWithoutResult(status -> {
                records.lock(record);
                try { chain.doFilter(new BufferedRequest(request, body), cachedResponse); }
                catch (IOException | ServletException failure) { throw new RequestFailure(failure); }
                if (request.isAsyncStarted()) { throw new IllegalStateException("Idempotent POST must be synchronous"); }
                if (cachedResponse.getStatus() >= 400) {
                    // MVC may handle the exception before it reaches this transaction boundary.
                    status.setRollbackOnly();
                    return;
                }
                Map<String, List<String>> headers = new LinkedHashMap<>();
                for (String header : List.of("Content-Type", "Location", "ETag", "Cache-Control", "Content-Language")) {
                    headers.put(header, List.copyOf(cachedResponse.getHeaders(header)));
                }
                records.complete(record, cachedResponse.getStatus(), anonymous ? capabilities.seal(cachedResponse.getContentAsByteArray(),record.id()) : cachedResponse.getContentAsByteArray(), headers);
            });
        } catch (RuntimeException failure) {
            records.abandon(record);
            if (failure instanceof RequestFailure wrapped) {
                if (wrapped.getCause() instanceof IOException io) { throw io; }
                throw (ServletException) wrapped.getCause();
            }
            throw failure;
        }
        if (cachedResponse.getStatus() >= 400) { records.abandon(record); }
        cachedResponse.copyBodyToResponse();
    }

    private com.fasterxml.jackson.databind.JsonNode json(byte[] bytes) {
        try { var json=mapper.readTree(bytes);if(json==null||!json.isObject()) throw new ApiException(ErrorCode.VALIDATION_ERROR);return json; }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        catch(IOException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
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
