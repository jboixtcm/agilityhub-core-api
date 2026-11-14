package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.SupportWriteAuditor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * S17 R-17-10 (AUTHZ-05, owner decision 13-11-2026): every write made with a support access is audited with `origin =
 * PLATFORM_SUPPORT`, also one whose service carries no `@Audited`. A write is a POST, PUT, PATCH or DELETE that its handler
 * answered 2xx: a refused one (4xx) changed nothing and a failed one (5xx) is undone by its transaction. The replay of an
 * `Idempotency-Key` ({@link IdempotentReplay}) is no write either: its handler does not run. After the handler,
 * {@link SupportWriteAuditor} writes `SUPPORT_WRITE` when the request left no audit entry of its own.
 */
public class SupportWriteAuditInterceptor implements HandlerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(SupportWriteAuditInterceptor.class);
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String STARTED = SupportWriteAuditInterceptor.class.getName() + ".started";
    private static final UrlPathHelper PATHS = new UrlPathHelper();
    private final SupportWriteAuditor auditor;

    public SupportWriteAuditInterceptor(SupportWriteAuditor auditor) { this.auditor = auditor; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST || !WRITES.contains(request.getMethod())
                || request.getAttribute(IdempotentReplay.ATTRIBUTE) != null) { return true; }
        var user = CurrentUser.current();
        if (user != null && user.support() != null) { request.setAttribute(STARTED, auditor.started()); }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        if (!(request.getAttribute(STARTED) instanceof Instant started)) { return; }
        request.removeAttribute(STARTED);
        int status = response.getStatus();
        var user = CurrentUser.current();
        if (failure != null || status < 200 || status >= 300 || user == null || user.support() == null) { return; }
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = PATHS.getPathWithinApplication(request);
        try {
            auditor.record(user.support().grantId(), started, request.getMethod(), route == null ? null : route.toString(), path, status);
        } catch (RuntimeException unavailable) {
            // The write is done and answered: the missing entry can only be reported. Never the body, the query or the token.
            LOG.error("SupportWriteAuditFailed grantId={} method={} path={}", user.support().grantId(), request.getMethod(), path, unavailable);
        }
    }
}
