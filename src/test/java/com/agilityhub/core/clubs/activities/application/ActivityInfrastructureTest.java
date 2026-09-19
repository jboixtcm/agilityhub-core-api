package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityInfrastructureTest {
    @Test void T_07_18_finishCommandValidatesSelectorsAndVisitsActiveClubs() {
        var clubs=mock(ClubConfigService.class);var lifecycle=mock(ActivityLifecycleService.class);
        var now=Instant.parse("2026-09-19T00:00:00Z");var command=new FinishActivitiesCommand(clubs,lifecycle,Clock.fixed(now,ZoneOffset.UTC));
        when(clubs.activeClubIds()).thenReturn(List.of("a","b"));when(clubs.findClubIdBySlug("example")).thenReturn(Optional.of("a"));
        assertThat(command.name()).isEqualTo("activities:finish-ended");
        command.run(new DefaultApplicationArguments("--core.command=activities:finish-ended"));
        command.run(new DefaultApplicationArguments("--core.command=activities:finish-ended","--club=example"));
        verify(lifecycle,times(3)).finishEnded(now);assertThat(TenantContext.current()).isNull();
        for(String[] args:List.of(new String[]{"extra"},new String[]{"--unknown=value"},new String[]{"--club"},new String[]{"--club=a","--club=b"}))
            assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--club=missing"))).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.CLUB_NOT_FOUND));
    }
    @Test void T_07_24_transactionsRetryOnlyTransientConflictsAndPreserveInterrupts() {
        var template=mock(TransactionTemplate.class);var service=new ActivityTransactions(template);
        var conflict=new com.mongodb.MongoException(112,"Example write conflict");
        try(var tenant=TenantContext.open("example")) {
            when(template.execute(any())).thenThrow(conflict).thenThrow(conflict).thenReturn("committed");
            assertThat(service.write(() -> "unused")).isEqualTo("committed");verify(template,times(3)).execute(any());
            reset(template);when(template.execute(any())).thenThrow(conflict);
            assertThatThrownBy(() -> service.write(() -> null)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
            verify(template,times(4)).execute(any());
            Thread.currentThread().interrupt();
            try { assertThatThrownBy(() -> service.write(() -> null)).isInstanceOf(IllegalStateException.class);assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
            finally { Thread.interrupted(); }
            reset(template);when(template.execute(any())).thenThrow(new IllegalArgumentException("Example invalid input"));
            assertThatThrownBy(() -> service.write(() -> null)).isInstanceOf(IllegalArgumentException.class);verify(template).execute(any());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThat(service.write(() -> "joined")).isEqualTo("joined"); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
        var labeled=new com.mongodb.MongoException(1,"Example transient failure");labeled.addLabel("TransientTransactionError");
        assertThat(ActivityTransactions.transientConflict(new IllegalStateException(labeled))).isTrue();
        assertThat(ActivityTransactions.transientConflict(new com.mongodb.MongoException(1,"Non-transient"))).isFalse();
    }
}
