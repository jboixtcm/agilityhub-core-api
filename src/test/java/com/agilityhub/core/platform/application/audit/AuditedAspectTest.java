package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.shared.domain.audit.AuditField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditedAspectTest {
    @Test void T_14_07_explicitBeforeExpressionCapturesMutableObjectAndUsesReturnedTarget() {
        AuditWriter writer = mock(AuditWriter.class);
        var proxy = proxy(writer, new DetachedService());
        MutableClub club = new MutableClub();
        assertThat(proxy.update(club, "Corrected name")).isSameAs(club);
        verify(writer).write(AuditAction.CLUB_UPDATED, "Club", "club-a", null, "Corrected name",
                List.of(new AuditChange("name", "before", "after")), null, null);
    }

    @Test void T_14_12_missingLoaderFailsBeforeMutationAndDuplicateLoadersAreRejected() {
        AuditWriter writer = mock(AuditWriter.class);
        DetachedService target = new DetachedService();
        assertThatThrownBy(() -> proxy(writer, target).missingLoader("club-a"))
                .hasMessage("Missing AuditableLoader for Club");
        assertThat(target.executed).isFalse();
        verifyNoInteractions(writer);
        AuditableLoader loader = new AuditableLoader() {
            @Override public String entityType() { return "Club"; }
            @Override public Object load(String entityId) { return null; }
        };
        assertThatThrownBy(() -> new AuditedAspect(writer, List.of(loader, loader))).isInstanceOf(IllegalStateException.class);
    }

    @Test void T_14_12_resultTargetWithoutLoaderSupportsNewObjects() {
        AuditWriter writer = mock(AuditWriter.class);
        proxy(writer, new DetachedService()).create();
        verify(writer).write(AuditAction.CLUB_UPDATED, "Club", "club-a", null, null,
                List.of(new AuditChange("name", null, "before")), null, null);
    }

    /**
     * E7-T07 (S14 §3; review #2 of E7-T04 round 3): `details` is an expression over the result, written as the entry's
     * `details` next to its changes (none here: the result has no audited field); a null map writes none, anything else
     * than a map is a programming error.
     */
    @Test void R_14_10_theDetailsExpressionIsTheEntrysDetails() {
        AuditWriter writer = mock(AuditWriter.class);
        var proxy = proxy(writer, new DetachedService());
        proxy.send("template-a", 3);
        verify(writer).write(AuditAction.ANNOUNCEMENT_SENT, "MessageTemplate", "template-a", null, null, List.of(),
                Map.of("batchId", "batch-template-a", "recipientCount", 3), null);
        proxy.send("template-b", 0);
        verify(writer).write(AuditAction.ANNOUNCEMENT_SENT, "MessageTemplate", "template-b", null, null, List.of(), null, null);
        assertThatThrownBy(() -> proxy.sendWithTextDetails("template-c")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("@Audited details must be a map");
    }

    /**
     * R-14-10 (E11-T17): `events` names the outbox events the entry links (each entry of a batch its own one); a null id is no
     * link, no expression links every event of the transaction (null), anything else than a collection is a programming error.
     */
    @Test void R_14_10_theEventsExpressionNamesTheEventsTheEntryLinks() {
        AuditWriter writer = mock(AuditWriter.class);
        var proxy = proxy(writer, new DetachedService());
        proxy.sendWithEvent("template-a", "event-a");
        verify(writer).write(AuditAction.ANNOUNCEMENT_SENT, "MessageTemplate", "template-a", null, null, List.of(), Map.of("batchId", "batch-template-a", "recipientCount", 1),
                List.of("event-a"));
        proxy.sendWithEvent("template-b", null);
        verify(writer).write(AuditAction.ANNOUNCEMENT_SENT, "MessageTemplate", "template-b", null, null, List.of(), Map.of("batchId", "batch-template-b", "recipientCount", 1),
                List.of());
        assertThatThrownBy(() -> proxy.sendWithTextEvents("template-c")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("@Audited events must be a collection of event ids");
    }

    /**
     * R-14-09, INC-40 (E11-T10): an audited method is refused without an active transaction, before it runs: nothing changes and
     * nothing is written.
     */
    @Test void R_14_09_INC_40_anAuditedMethodWithoutTransactionIsRefusedBeforeItRuns() {
        AuditWriter writer = mock(AuditWriter.class);
        var factory = new AspectJProxyFactory(new DetachedService());
        factory.addAspect(new AuditedAspect(writer, List.of()));
        DetachedService proxy = factory.getProxy();
        MutableClub club = new MutableClub();
        assertThatThrownBy(() -> proxy.update(club, "Corrected name")).isInstanceOf(IllegalStateException.class)
                .hasMessage("@Audited needs an active transaction (R-14-09): DetachedService.update");
        assertThat(club.name).isEqualTo("before");
        verifyNoInteractions(writer);
    }

    /** The calls of the other tests run as inside a transaction (R-14-09): the aspect only checks that one is active. */
    private DetachedService proxy(AuditWriter writer, DetachedService service) {
        var factory = new AspectJProxyFactory(service);
        factory.addAspect(new AuditedAspect(writer, List.of()));
        // Outermost, as the transaction interceptor (order 0) is around the aspect (order 100).
        factory.addAdvice(0, (org.aopalliance.intercept.MethodInterceptor) invocation -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try { return invocation.proceed(); }
            finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
        });
        return factory.getProxy();
    }

    public static class MutableClub {
        @AuditField private String name = "before";
        public String id() { return "club-a"; }
    }

    public record Sent(String templateId, int recipientCount) {
        public Map<String, Object> details() { return recipientCount == 0 ? null : Map.of("batchId", "batch-" + templateId, "recipientCount", recipientCount); }
    }

    public static class DetachedService {
        boolean executed;
        @Audited(action = AuditAction.CLUB_UPDATED, entityType = "'Club'", before = "#p0", reason = "#p1")
        public MutableClub update(MutableClub club, String reason) {
            club.name = "after";
            return club;
        }

        @Audited(action = AuditAction.CLUB_UPDATED, entityType = "'Club'", entity = "#p0")
        public MutableClub missingLoader(String id) { executed = true; return new MutableClub(); }

        @Audited(action = AuditAction.CLUB_UPDATED, entityType = "'Club'")
        public MutableClub create() { return new MutableClub(); }

        @Audited(action = AuditAction.ANNOUNCEMENT_SENT, entityType = "'MessageTemplate'", entity = "#result.templateId()", details = "#result.details()")
        public Sent send(String templateId, int recipientCount) { return new Sent(templateId, recipientCount); }

        @Audited(action = AuditAction.ANNOUNCEMENT_SENT, entityType = "'MessageTemplate'", entity = "#result.templateId()", details = "'not a map'")
        public Sent sendWithTextDetails(String templateId) { return new Sent(templateId, 1); }

        @Audited(action = AuditAction.ANNOUNCEMENT_SENT, entityType = "'MessageTemplate'", entity = "#result.templateId()", details = "#result.details()", events = "{#p1}")
        public Sent sendWithEvent(String templateId, String eventId) { return new Sent(templateId, 1); }

        @Audited(action = AuditAction.ANNOUNCEMENT_SENT, entityType = "'MessageTemplate'", entity = "#result.templateId()", events = "'event-a'")
        public Sent sendWithTextEvents(String templateId) { return new Sent(templateId, 1); }
    }
}
