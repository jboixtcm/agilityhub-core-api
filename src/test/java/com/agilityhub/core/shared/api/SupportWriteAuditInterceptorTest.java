package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.SupportWriteAuditor;
import com.agilityhub.core.shared.domain.DomainEvent;
import jakarta.servlet.DispatcherType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * S17 R-17-10 (AUTHZ-05, owner decision 13-11-2026): every write of a support access reaches the audit, also the ones whose
 * service audits nothing. The interceptor hands each POST, PUT, PATCH or DELETE of a support token that its handler answered 2xx
 * to {@link SupportWriteAuditor}, with the moment it started; a read, a refusal, a failure, a replayed `Idempotency-Key` and every
 * request of a token that is no support access are left alone.
 */
class SupportWriteAuditInterceptorTest {
    static final Instant STARTED = Instant.parse("2026-11-13T10:00:00Z");
    static final CurrentUser SUPPORT = new CurrentUser("platform-a", "Pat Support", null, DomainEvent.Origin.BACKOFFICE,
            new CurrentUser.Support("grant-a", "platform-a", "Pat Support", STARTED.plusSeconds(3600)));
    static final CurrentUser ADMIN = new CurrentUser("admin-a", "Club Admin", null, DomainEvent.Origin.BACKOFFICE);

    final SupportWriteAuditor auditor = mock(SupportWriteAuditor.class);
    final SupportWriteAuditInterceptor interceptor = new SupportWriteAuditInterceptor(auditor);

    MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/saved-views/{id}");
        return request;
    }

    void run(CurrentUser user, MockHttpServletRequest request, int status, Exception failure) {
        var response = new MockHttpServletResponse();
        try (var scope = user == null ? null : CurrentUser.open(user)) {
            interceptor.preHandle(request, response, new Object());
            response.setStatus(status);
            interceptor.afterCompletion(request, response, new Object(), failure);
        }
    }

    @Test void AUTHZ_05_aSupportWriteAnsweredTwoHundredIsHandedToTheAuditWithItsRouteAndStart() {
        when(auditor.started()).thenReturn(STARTED);
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            run(SUPPORT, request(method, "/api/v1/saved-views/view-a"), "POST".equals(method) ? 201 : 200, null);
            verify(auditor).record("grant-a", STARTED, method, "/api/v1/saved-views/{id}", "/api/v1/saved-views/view-a", "POST".equals(method) ? 201 : 200);
        }
        // A 204 is a write too; without a matched pattern the route is unknown, never invented.
        var noRoute = new MockHttpServletRequest("DELETE", "/api/v1/saved-views/view-b");
        run(SUPPORT, noRoute, 204, null);
        verify(auditor).record("grant-a", STARTED, "DELETE", null, "/api/v1/saved-views/view-b", 204);
    }

    @Test void AUTHZ_05_readsRefusalsFailuresReplaysAndOtherTokensAreNotSupportWrites() {
        when(auditor.started()).thenReturn(STARTED);
        // A read, even of a support token.
        run(SUPPORT, request("GET", "/api/v1/members/m-1"), 200, null);
        run(SUPPORT, request("HEAD", "/api/v1/members/m-1"), 200, null);
        // A refused or failed write changed nothing.
        run(SUPPORT, request("POST", "/api/v1/saved-views"), 409, null);
        run(SUPPORT, request("PATCH", "/api/v1/saved-views/view-a"), 500, null);
        run(SUPPORT, request("POST", "/api/v1/saved-views"), 200, new IllegalStateException("boom"));
        // A replayed Idempotency-Key answers the stored response: its handler does not run.
        var replay = request("POST", "/api/v1/saved-views");
        IdempotentReplay.stored(201, Map.of(), new byte[0]).pending(replay);
        run(SUPPORT, replay, 201, null);
        // An async or error dispatch is not the request itself.
        var async = request("POST", "/api/v1/saved-views");
        async.setDispatcherType(DispatcherType.ASYNC);
        run(SUPPORT, async, 201, null);
        var error = request("POST", "/api/v1/saved-views");
        error.setDispatcherType(DispatcherType.ERROR);
        run(SUPPORT, error, 201, null);
        // The club's own admin and an anonymous request are no support access.
        run(ADMIN, request("POST", "/api/v1/saved-views"), 201, null);
        run(null, request("POST", "/api/v1/saved-views"), 201, null);
        verify(auditor, never()).record(anyString(), any(), anyString(), any(), anyString(), anyInt());
    }

    @Test void AUTHZ_05_anAuditThatCannotBeWrittenNeverTurnsTheAnsweredWriteIntoAnError() {
        when(auditor.started()).thenReturn(STARTED);
        when(auditor.record(anyString(), any(), anyString(), any(), anyString(), anyInt())).thenThrow(new IllegalStateException("database down"));
        var request = request("POST", "/api/v1/saved-views");
        assertThatCode(() -> run(SUPPORT, request, 201, null)).doesNotThrowAnyException();
        verify(auditor).record("grant-a", STARTED, "POST", "/api/v1/saved-views/{id}", "/api/v1/saved-views", 201);
        // The start is taken once per request: a second completion of the same request writes nothing more.
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        verify(auditor, times(1)).record(anyString(), any(), anyString(), any(), anyString(), anyInt());
    }
}
