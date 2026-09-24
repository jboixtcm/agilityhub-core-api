package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.MockClock;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateQueryTest {
    @Test void T_06_10_templateCacheIsTenantLocaleBoundedExpiresAndInvalidatesAfterCommitOnly() throws Exception {
        var repository = mock(WeekTemplateRepository.class); var context = mock(PlanningContext.class); var config = mock(ClubConfig.class);
        var clock = new MockClock(Instant.parse("2026-08-24T00:00:00Z"));
        when(context.config()).thenReturn(config); when(config.modules()).thenReturn(Set.of());
        when(context.catalog()).thenReturn(new SchedulingCatalog(List.of(), List.of(), List.of()));
        when(context.detector()).thenReturn(new InconsistencyDetector(new IcuMessageSource()::format));
        var template = new WeekTemplate("template", "club-a", "Original", TemplateKind.WEEKDAYS, null, true, List.of(), List.of(), 0L, clock.instant(), "a", clock.instant(), "a");
        when(repository.findById("template")).thenReturn(Optional.of(template));
        var query = new TemplateQuery(repository, context, clock);
        try (var tenant = TenantContext.open("club-a"); var locale = LocaleContext.open(Locale.ENGLISH)) {
            query.get("template"); query.get("template"); verify(repository, times(1)).findById("template");
            try (var otherLocale = LocaleContext.open(Locale.forLanguageTag("es"))) { query.get("template"); }
            verify(repository, times(2)).findById("template");
            clock.advance(Duration.ofSeconds(61)); query.get("template"); verify(repository, times(3)).findById("template");
            TransactionSynchronizationManager.initSynchronization();
            try {
                query.invalidateAfterCommit("club-a"); query.get("template"); verify(repository, times(3)).findById("template");
                TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
            query.get("template"); verify(repository, times(4)).findById("template");
            query.invalidateAfterCommit("other"); query.get("template"); verify(repository, times(4)).findById("template");
            query.invalidateAfterCommit("club-a"); query.get("template"); verify(repository, times(5)).findById("template");
        }
        try (var tenant = TenantContext.open("club-b"); var locale = LocaleContext.open(Locale.ENGLISH)) {
            when(repository.findById("template")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> query.get("template")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }
    @Test void T_06_10_eachCatalogAndTemplateSubscriptionEvictsOnlyItsClubAfterCommit() throws Exception {
        var query = mock(TemplateQuery.class); var beans = new PlanningEvents();
        var handlers = List.of(beans.planningWeekTemplateChanged(query), beans.planningLevelChanged(query), beans.planningRingChanged(query), beans.planningInstructorChanged(query), beans.planningParameterChanged(query));
        assertThat(handlers).extracting(DomainEventHandler::eventType).containsExactly("WeekTemplateChanged", "LevelChanged", "RingChanged", "InstructorChanged", "ParameterChanged");
        for (var handler : handlers) {
            assertThat(handler.eventClass()).isEqualTo(PlanningEvents.Event.class);
            // E3-T09 step 5: a handler runs after the writer's commit (outbox delivery, or on this instance right after
            // the commit), so it evicts at once; a synchronization registered inside `afterCommit` would never fire.
            assertThat(handler.evictsAfterCommit()).isTrue();
            handler.handle("event", new PlanningEvents.Event(handler.eventType(), "club", "WeekTemplate", "template", Instant.EPOCH, Map.of(), "account", null, DomainEvent.Origin.BACKOFFICE));
        }
        verify(query, times(5)).invalidate("club");
    }
}
