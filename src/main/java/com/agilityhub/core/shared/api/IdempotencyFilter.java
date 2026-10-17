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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(IdempotencyFilter.class);
    private final IdempotencyRepository records;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ApiExceptionHandler errors;
    private final ObjectMapper mapper;
    private com.agilityhub.core.shared.application.SignupCapabilities capabilities;
    @org.springframework.beans.factory.annotation.Autowired
    public void capabilities(com.agilityhub.core.shared.application.SignupCapabilities capabilities) { this.capabilities = capabilities; }

    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.shared.application.SignupCheckoutGuard signupCheckout;
    /** INC-49 round 2 (E123): a club whose settings load exists; a console key is claimed only in an existing club. */
    private com.agilityhub.core.shared.application.LocaleSettingsProvider clubs;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void clubs(com.agilityhub.core.shared.application.LocaleSettingsProvider clubs) { this.clubs = clubs; }
    /**
     * INC-49 round 2 (E123, review #3): the routes are matched on the path Spring routes (decoded, without `;` parameters), as
     * `RateLimitFilter` matches them, so an encoded spelling of a route takes the same own-transaction, sealing and club scope.
     */
    private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();

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
     * S12 R-12-11/14/16/17/19 (E8-T02), `METHOD path`: the billing writes take the club's billing lock outside any transaction
     * (the run, its rollback, a simulation sent with a key) and run their own transaction retried on a write conflict
     * (`BillingTransactions`), storing their answer there: the run, the rollback, a manual invoice, the payments, the failure
     * and the cancellation of an invoice.
     */
    static final java.util.regex.Pattern BILLING = java.util.regex.Pattern.compile("POST /api/v1/billing/(simulations|runs|runs/[^/]+/(rollback|card-charges))"
            + "|POST /api/v1/invoices(/payments)?|POST /api/v1/invoices/[^/]+/(payment|failure|cancellation|retry|refund)"
            + "|POST /api/v1/pack-balances(/[^/]+/adjustments)?|POST /api/v1/upfront-payments|POST /api/v1/members/[^/]+/plan-change|POST /api/v1/upfront-payments/[^/]+/refund|POST /api/v1/members/[^/]+/card-setup-link|POST /api/v1/me/card-setup");

    /**
     * S16 R-16-11 (E9-T06, INC-47's pattern), `METHOD path`: the keyed build-session writes (the start, the join and `finish`) run
     * in `BuildSessionService`'s own transaction, retried whole on a write conflict or a join code already taken, and store
     * their answer there; exhausted retries are `409 STALE_VERSION`, never a 500. The taps (`PATCH`) are never keyed.
     */
    static final java.util.regex.Pattern BUILD_SESSIONS = java.util.regex.Pattern.compile("POST /api/v1/build-sessions(/join|/[^/]+/finish)?");

    /**
     * S17 R-17-03 (E10-T04), `METHOD path`: the keyed domain verification looks DNS up before its write, then runs
     * `ConsoleTransactions.keyed`'s own transaction and stores its answer there; no network call holds a Mongo transaction open.
     */
    static final java.util.regex.Pattern CONSOLE = java.util.regex.Pattern.compile("POST /api/v1/platform/clubs/[^/]+/domains/[^/]+/verification");

    /**
     * INC-49 (E10-T04): a console route names its club in the path (`/platform/clubs/{id}/…`). The key is scoped to that club,
     * never to the token's (a platform token has none); the route's own platform authorization judges the request, before a
     * replay too (E62). Other platform routes have no club: their keys are scoped to none.
     */
    static final java.util.regex.Pattern CONSOLE_CLUB = java.util.regex.Pattern.compile("/api/v1/platform/clubs/([^/]+)/.+");

    /**
     * S17 R-17-10/R-17-11 (E10-T04): the answers that carry a credential (a support access token, a public key shown once) are
     * stored sealed with the record's id, as an anonymous signup's are, and opened only to replay them.
     */
    static final java.util.regex.Pattern SEALED = java.util.regex.Pattern.compile("POST /api/v1/platform/clubs/[^/]+/(support-access|public-api-key)");

    /** S13 mutations retry and store the response in LifecycleTransactions, including optional keyed admin actions. */
    static final java.util.regex.Pattern LIFECYCLE = java.util.regex.Pattern.compile("POST /api/v1/(me/)?inactivity-periods(/[^/]+/(decision|termination|cancellation))?"
            + "|POST /api/v1/(me/)?leave-requests(/[^/]+/(decision|cancellation))?"
            + "|POST /api/v1/members/[^/]+/(leave|reactivation)|DELETE /api/v1/members/[^/]+/planned-leave");

    /**
     * CONVENCIONS_API §7 (E5-T27, ruling E46): a POST with the header, and every other route whose handler declares the header
     * ({@link KeyedRoutes}: E6-T01's attendance and observations PUTs and the two follow-up DELETEs today), are filtered; a read never.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        boolean keyedMethod = "POST".equals(request.getMethod()) || keyed.declares(request.getMethod(), path);
        return HealthRequests.matches(request) || !keyedMethod || (request.getHeader(KeyedRoutes.HEADER) == null && !path.equals("/api/v1/signup"));
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
        String path = PATHS.getPathWithinApplication(request);
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
            // S16/S17: platform commands have no request tenant. Role and impersonation guards still run before a replay. A
            // console route's claim uses its path's club (INC-49, {@link #CONSOLE_CLUB}), decoded as the handler receives it;
            // any other platform claim uses clubId=null.
            boolean platformCommand = path.startsWith("/api/v1/platform/");
            var console = CONSOLE_CLUB.matcher(path);
            clubId = !platformCommand ? jwt.getToken().getClaimAsString("clubId") : console.matches() ? console.group(1) : null;
            accountId = jwt.getToken().getSubject();
            if ((!platformCommand && (clubId == null || clubId.isBlank())) || accountId == null || accountId.isBlank()) {
                throw new ApiException(ErrorCode.NO_MEMBERSHIP);
            }
            if (platformCommand && clubId != null) {
                // INC-49 round 2 (E123): idempotency rows are tenant rows, so a club that does not exist gets none; its handler
                // answers 404 CLUB_NOT_FOUND without one.
                if (clubs != null && clubs.settings(clubId).isEmpty()) { chain.doFilter(new BufferedRequest(request, body), response); return; }
                keyed(request, response, chain, path, body, false, clubId, accountId, authentication, new Rows(records, clubId, true));
                return;
            }
        }
        keyed(request, response, chain, path, body, anonymous, clubId, accountId, authentication, new Rows(records, clubId, false));
    }

    /**
     * INC-49 (E10-T04): the idempotency rows are tenant rows. A console route's rows live in its path's club, which is no
     * request tenant (the handler opens it, and may open another club, a clone's source, meanwhile): each row call opens that
     * club itself unless it is the one open already. Every other request's rows use its request tenant, as before.
     */
    private record Rows(IdempotencyRepository records, String clubId, boolean console) {
        <T> T in(java.util.function.Supplier<T> work) {
            if (!console || clubId.equals(com.agilityhub.core.shared.application.TenantContext.current())) { return work.get(); }
            try (var scope = com.agilityhub.core.shared.application.TenantContext.open(clubId)) { return work.get(); }
        }
        IdempotencyRepository.Claim claim(String club, String account, String key, String hash, java.time.Instant now) { return in(() -> records.claim(club, account, key, hash, now)); }
        void lock(com.agilityhub.core.shared.persistence.IdempotencyRecord record) { in(() -> { records.lock(record); return null; }); }
        void complete(com.agilityhub.core.shared.persistence.IdempotencyRecord record, int status, byte[] body, Map<String, List<String>> headers) {
            in(() -> { records.complete(record, status, body, headers); return null; });
        }
        void abandon(com.agilityhub.core.shared.persistence.IdempotencyRecord record) { in(() -> { records.abandon(record); return null; }); }
        boolean held(com.agilityhub.core.shared.persistence.IdempotencyRecord record) { return in(() -> records.held(record)); }
    }

    private void keyed(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String path, byte[] body, boolean anonymous,
            String clubId, String accountId, org.springframework.security.core.Authentication authentication, Rows records) throws IOException, ServletException {
        boolean publicSignup = path.equals("/api/v1/signup");
        boolean checkout = path.equals("/api/v1/checkout-sessions");
        boolean sealed = anonymous || SEALED.matcher(request.getMethod() + " " + path).matches();
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
                // A live claim (E79): one past its lease was already taken over by records.claim, so this is a request in progress.
                replay = IdempotentReplay.refused(new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "IN_PROGRESS")));
            } else {
                replay = IdempotentReplay.stored(record.responseStatus(), record.responseHeaders(),
                        sealed ? capabilities.open(record.responseBody(), record.id()) : record.responseBody());
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
        // provider call runs between its two transactions, and its session keeps this request's reference, so a retry after a
        // lost answer finds the same session.
        boolean signup = publicSignup || path.equals("/api/v1/me/dogs/signup") || checkout;
        // S10 (E6-T03 rounds 4 and 5, INC-47): two keyed follow-up writes that meet on one document answer the spec's conflict
        // code (STALE_VERSION, TASK_ALREADY_DONE, NOT_FOUND, …) or both succeed, never a 500; like the signup, an error releases the key.
        boolean followup = FOLLOWUP.matcher(request.getMethod() + " " + path).matches();
        // S12 (E8-T02): the billing writes, like the follow-up ones; an error releases the key.
        boolean billing = BILLING.matcher(request.getMethod() + " " + path).matches();
        // S16 (E9-T06): the keyed build-session writes, like the follow-up ones; an error releases the key.
        boolean buildSessions = BUILD_SESSIONS.matcher(request.getMethod() + " " + path).matches();
        // S17 (E10-T04): the keyed domain verification, like the follow-up writes; an error releases the key.
        boolean console = CONSOLE.matcher(request.getMethod() + " " + path).matches();
        if (bookings || signup || followup || billing || buildSessions || console || LIFECYCLE.matcher(request.getMethod() + " " + path).matches() || path.equals("/api/v1/activities") || path.startsWith("/api/v1/activities/")
                || path.equals("/api/v1/activity-registrations") || path.startsWith("/api/v1/activity-registrations/")) {
            var completed = new java.util.concurrent.atomic.AtomicBoolean();
            var target = bookings ? new ContentCachingResponseWrapper(response) : response;
            var operation = com.agilityhub.core.shared.application.IdempotentOperation.open(
                    () -> records.lock(record), (status, bytes) -> {
                        // A 204 (a follow-up DELETE or read mark) has no body and so no Content-Type, as the request transaction's path stores it.
                        var language = List.of(com.agilityhub.core.shared.application.LocaleContext.current().toLanguageTag());
                        records.complete(record, status, sealed ? capabilities.seal(bytes, record.id()) : bytes, bytes.length == 0
                                ? Map.of("Content-Language", language) : Map.of("Content-Type", List.of("application/json"), "Content-Language", language));
                        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override public void afterCommit() { completed.set(true); }
                                });
                    }, reference(clubId, accountId, key, hash));
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
        // E5-T29 round 2 (CONVENCIONS_API §7, E75): the handler knows this request's reference. A keyed job trigger runs outside
        // this transaction and keeps the reference on its run, so the retry after a lost answer answers that run.
        try (var named = com.agilityhub.core.shared.application.IdempotentOperation.referenced(reference(clubId, accountId, key, hash))) {
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
                records.complete(record, cachedResponse.getStatus(), sealed ? capabilities.seal(cachedResponse.getContentAsByteArray(),record.id()) : cachedResponse.getContentAsByteArray(), headers);
            });
        } catch (RuntimeException failure) {
            // E79 (E5-T30 round 2): a retry took this request's claim over after its lease. The answer could not be stored (the
            // record is gone, or a write conflict on it) and the effects rolled back: it answers as any attempt meanwhile.
            // E5-T31 (review E5-T30 #5): Mongo may be what failed. Checking or releasing the claim then fails too, and that
            // failure never replaces the original one: the record counts as held and the second failure goes along as suppressed.
            Throwable original = failure instanceof RequestFailure wrapped ? wrapped.getCause() : failure;
            boolean held = true;
            try { held = failure instanceof ApiException || records.held(record); }
            catch (RuntimeException unchecked) {
                LOG.warn("Idempotency held check failed: recordId={} clubId={}", record.id(), record.clubId());
                original.addSuppressed(unchecked);
            }
            try { records.abandon(record); }
            catch (RuntimeException unreleased) {
                LOG.warn("Idempotency abandon failed: recordId={} clubId={}", record.id(), record.clubId());
                original.addSuppressed(unreleased);
            }
            if (!held) { throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "IN_PROGRESS")); }
            // INC-47 (E11-T02): a write conflict on the stored answer or at commit rolled the effects back and released the key:
            // the client retries it (409 STALE_VERSION), never a 500.
            if (com.agilityhub.core.shared.application.TransactionRetries.retryableConflict(original)) {
                LOG.warn("Idempotent request hit a transient transaction failure: recordId={} clubId={}", record.id(), record.clubId());
                throw new ApiException(ErrorCode.STALE_VERSION);
            }
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

    /**
     * E5-T28 (CONVENCIONS_API §7): the same request under the same key and scope, whichever record claims the key now (an
     * abandoned record is removed, and the retry claims a new one). A digest: the key and the account never leave the filter.
     */
    static String reference(String clubId, String accountId, String key, String hash) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((clubId + "\n" + accountId + "\n" + key + "\n" + hash).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String hash(HttpServletRequest request, byte[] body, org.springframework.security.core.Authentication authentication) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            // The route Spring runs (E123): two spellings of one path are one request.
            String target = request.getMethod() + "\n" + PATHS.getPathWithinApplication(request) + "\n"
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
