package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.shared.domain.audit.AuditField;
import java.util.List;
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
                List.of(new AuditChange("name", "before", "after")));
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
                List.of(new AuditChange("name", null, "before")));
    }

    private DetachedService proxy(AuditWriter writer, DetachedService service) {
        var factory = new AspectJProxyFactory(service);
        factory.addAspect(new AuditedAspect(writer, List.of()));
        return factory.getProxy();
    }

    public static class MutableClub {
        @AuditField private String name = "before";
        public String id() { return "club-a"; }
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
    }
}
