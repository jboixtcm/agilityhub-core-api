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
import java.util.Set;
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
                             ApiExceptionHandler errors, ObjectMapper mapper, KeyedRoutes keyed) {
        this.records = records;
        this.transactions = transactions;
        this.clock = clock;
        this.errors = errors;
        this.mapper = mapper;
        this.keyed = keyed;
    }
    private final KeyedRoutes keyed;
    KeyedRoutes keyed() { return keyed; }

    /** S10 R-10-04 (E6-T02): the attendance save retries inside its seat-lock transaction and stores its 200 there. */
    static final java.util.regex.Pattern ATTENDANCE = java.util.regex.Pattern.compile("/api/v1/class-sessions/[^/]+/attendance");
    /**
     * S10 R-10-10 to R-10-13 (E6-T03 rounds 4 and 5, INC-47), `METHOD path`: the keyed follow-up writes retry a write conflict
     * inside their own transaction (`FollowupTransactions`) and store their answer there: the observations save, the task's
     * creation, deletion, completion and reopening, the attachments' upload URL, registration and removal, and the read
     * marks. The upload URL only inserts a new grant, but the first grant of a database creates the grants' collection,
     * which two transactions cannot both do (a write conflict at commit).
     */
    static final java.util.regex.Pattern FOLLOWUP = java.util.regex.Pattern.compile("PUT /api/v1/dogs/[^/]+/observations|POST /api/v1/tasks"
            + "|DELETE /api/v1/tasks/[^/]+|POST /api/v1/tasks/[^/]+/(completion|reopening)|POST /api/v1/attachments(/upload-url)?|DELETE /api/v1/attachments/[^/]+"
            + "|POST /api/v1/followup/[^/]+/read|POST /api/v1/followup/read-all");

    /**
     * CONVENCIONS_API §7 (E5-T27, ruling E46): a POST with the header, and every other route whose handler declares the header
     * ({@link KeyedRoutes}: E6-T01's attendance and observations PUTs and the two follow-up DELETEs today), are filtered; a read never.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean keyedMethod = "POST".equals(request.getMethod()) || keyed.declares(request.getMethod(), path);
        return HealthRequests.matches(request) || !keyedMethod || (request.getHeader(KeyedRoutes.HEADER) == null && !request.getRequestURI().equals("/api/v1/signup"));
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
        String hash = hash(request, body, authentication);
        var claim = records.claim(clubId, accountId, key, hash, clock.instant());
        var record = claim.record();
        if (!claim.acquired()) {
            // CONVENCIONS_API §7 (E62): nothing of the stored record is answered here. The request goes on to its handler,
            // whose interceptors and @PreAuthorize judge the current token; IdempotentReplayAspect then answers in its place.
            IdempotentReplay replay;
            if (!record.requestHash().equals(hash)) {
                replay = IdempotentReplay.refused(new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "DIFFERENT_REQUEST")));
            } else if (record.status() == com.agilityhub.core.shared.persistence.IdempotencyRecord.Status.IN_PROGRESS) {
                replay = IdempotentReplay.refused(new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "IN_PROGRESS")));
            } else {
                replay = IdempotentReplay.stored(record.responseStatus(), record.responseHeaders(),
                        anonymous ? capabilities.open(record.responseBody(), record.id()) : record.responseBody());
            }
            replay.pending(request);
            chain.doFilter(new BufferedRequest(request, body), response);
            return;
        }
        // S07 executes serialization inside its retryable use-case transaction. Other routes keep
        // the existing request transaction. Both paths commit the idempotency row with effects.
        // S08 confirmation and claim replay their business conflicts too (R-08-08: «també si va ser 409»).
        // S09 bookings and cancellations retry DuplicateKey/WriteConflict inside their own transaction (R-09-06) and replay likewise.
        // S10 R-10-04: the attendance save retries inside its seat-lock transaction and stores its 200 there too.
        // S10 R-10-10 to R-10-13: the keyed follow-up writes ({@link #FOLLOWUP}) do the same in FollowupTransactions.
        boolean bookings = path.equals("/api/v1/bookings") || path.matches("/api/v1/waitlist-entries/[^/]+/claim")
                || path.equals("/api/v1/training-bookings") || path.matches("/api/v1/training-bookings/[^/]+/cancellation")
                || "PUT".equals(request.getMethod()) && ATTENDANCE.matcher(path).matches();
        // E3-T09 (R-04-27): the signup submissions retry a write conflict inside their own transaction (SignupTransactions),
        // so two concurrent submissions give one 201 and one 422, never a 500. E5-T28 (A3-06): the signup checkout too; its
        // provider call runs between its two transactions.
        boolean signup = publicSignup || path.equals("/api/v1/me/dogs/signup") || checkout;
        // S10 (E6-T03 rounds 4 and 5, INC-47): two keyed follow-up writes that meet on one document answer the spec's conflict
        // code (STALE_VERSION, TASK_ALREADY_DONE, NOT_FOUND, …) or both succeed, never a 500; like the signup, an error releases the key.
        boolean followup = FOLLOWUP.matcher(request.getMethod() + " " + path).matches();
        if (bookings || signup || followup || path.equals("/api/v1/activities") || path.startsWith("/api/v1/activities/")
                || path.equals("/api/v1/activity-registrations") || path.startsWith("/api/v1/activity-registrations/")) {
            var completed = new java.util.concurrent.atomic.AtomicBoolean();
            var target = bookings ? new ContentCachingResponseWrapper(response) : response;
            var operation = com.agilityhub.core.shared.application.IdempotentOperation.open(
                    () -> records.lock(record), (status, bytes) -> {
                        // A 204 (a follow-up DELETE or read mark) has no body and so no Content-Type, as the request transaction's path stores it.
                        var language = List.of(com.agilityhub.core.shared.application.LocaleContext.current().toLanguageTag());
                        records.complete(record, status, anonymous ? capabilities.seal(bytes, record.id()) : bytes, bytes.length == 0
                                ? Map.of("Content-Language", language) : Map.of("Content-Type", List.of("application/json"), "Content-Language", language));
                        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override public void afterCommit() { completed.set(true); }
                                });
                    });
            try {
                chain.doFilter(new BufferedRequest(request, body), target);
            } finally {
                operation.close();
                if (!completed.get()) {
                    if (!operation.released() && target instanceof ContentCachingResponseWrapper cached && (cached.getStatus() == 409 || cached.getStatus() == 422)
                            && !transientOutcome(cached.getContentAsByteArray())) {
                        transactions.executeWithoutResult(status -> {
                            records.lock(record);
                            records.complete(record, cached.getStatus(), cached.getContentAsByteArray(), Map.of("Content-Type", List.of("application/json"),
                                    "Content-Language", List.copyOf(cached.getHeaders("Content-Language"))));
                        });
                    } else { records.abandon(record); }
                }
                if (target instanceof ContentCachingResponseWrapper cached) { cached.copyBodyToResponse(); }
            }
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

    /** Exhausted write-conflict retries are not a business outcome: the key is released instead of replaying them. */
    private boolean transientOutcome(byte[] bytes) {
        try { return Set.of("STALE_VERSION", "IDEMPOTENCY_KEY_REUSED").contains(mapper.readTree(bytes).path("code").asText()); }
        catch (IOException unreadable) { return true; }
    }

    private com.fasterxml.jackson.databind.JsonNode json(byte[] bytes) {
        try { var json=mapper.readTree(bytes);if(json==null||!json.isObject()) throw new ApiException(ErrorCode.VALIDATION_ERROR);return json; }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        catch(IOException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }

    /**
     * CONVENCIONS_API §7 (E62): the caller's roles and the impersonating actor are part of the request. Guards that a
     * handler keeps in code (for example the attachments' purpose) cannot run before a replay, so a stored answer is only
     * replayed to the same authorization context; any other one gets `IDEMPOTENCY_KEY_REUSED {reason: DIFFERENT_REQUEST}`
     * once the route's own authorization has accepted it.
     */
    static String authorization(org.springframework.security.core.Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) { return ""; }
        var roles = jwt.getAuthorities().stream().map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_")).sorted().toList();
        return String.join(",", roles) + ";" + java.util.Objects.toString(jwt.getToken().getClaimAsString("actorAccountId"), "");
    }

    private String hash(HttpServletRequest request, byte[] body, org.springframework.security.core.Authentication authentication) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String target = request.getMethod() + "\n" + request.getRequestURI() + "\n"
                    + request.getQueryString() + "\n" + request.getContentType() + "\n" + authorization(authentication) + "\n";
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
