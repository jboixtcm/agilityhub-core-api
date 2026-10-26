package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.MigrationWelcomeRecipients;
import com.agilityhub.core.identity.application.MigrationWelcomeDelivery;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationWelcomeCommandTest {
    final MigrationClubAccess clubs = mock(MigrationClubAccess.class);
    final MigrationWelcomeRecipients recipients = mock(MigrationWelcomeRecipients.class);
    final MigrationWelcomeDelivery delivery = mock(MigrationWelcomeDelivery.class);
    @SuppressWarnings("unchecked") final Consumer<Duration> pause = mock(Consumer.class);
    final MigrationWelcomeCommand command = new MigrationWelcomeCommand(clubs, recipients, delivery, pause);
    @AfterEach void clear() { TenantContext.clear(); }
    MigrationWelcomeRecipients.Recipient recipient(int number, String account) {
        return new MigrationWelcomeRecipients.Recipient("member-" + number, account, number, "Fictional", "OTHER");
    }
    @Test void T_18_09_staffFirstSeparateBatchAndRerunSkipsDoNotConsumeTheSendBudget() {
        when(clubs.resolve("sample")).thenReturn("club");
        var member = recipient(1,"member"); var staff = recipient(9,"staff"); var duplicate = recipient(2,"member");
        when(recipients.list(Set.of())).thenReturn(List.of(member, duplicate, staff));
        when(delivery.staff("staff")).thenReturn(true);
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(false))).thenAnswer(call -> {
            assertThat(TenantContext.require()).isEqualTo("club");
            return MigrationWelcomeDelivery.Result.SENT;
        });
        command.run(new DefaultApplicationArguments("--club=sample", "--batch=1", "--interval=2m"));
        var order = inOrder(delivery, pause);
        order.verify(delivery).deliver("member-9", "staff", "Fictional", "OTHER", false);
        order.verify(pause).accept(Duration.ofMinutes(2));
        order.verify(delivery).deliver("member-1", "member", "Fictional", "OTHER", false);
        verify(delivery, times(2)).deliver(anyString(), anyString(), anyString(), anyString(), eq(false));
        assertThat(TenantContext.current()).isNull();
        assertThat(command.name()).isEqualTo("migration:welcome");
    }
    @Test void T_18_09_previewFiltersNumbersAndNeverSleepsOrSends() {
        when(clubs.resolve("sample")).thenReturn("club");
        when(recipients.list(Set.of(1,2))).thenReturn(List.of(recipient(1,"a"),recipient(2,"b")));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(MigrationWelcomeDelivery.Result.ELIGIBLE);
        command.run(new DefaultApplicationArguments("--club=sample", "--only-numbers=1,2", "--dry-run"));
        verifyNoInteractions(pause);
        verify(delivery, never()).deliver(anyString(), anyString(), anyString(), anyString(), eq(false));
    }
    @Test void T_18_09_uncertainDeliveryReturnsANonzeroCommandOutcomeWithoutRetrying() {
        when(clubs.resolve("sample")).thenReturn("club");
        when(recipients.list(Set.of())).thenReturn(List.of(recipient(1,"a")));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(MigrationWelcomeDelivery.Result.REVIEW_REQUIRED);
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--club=sample")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("requires review");
        assertThat(TenantContext.current()).isNull();
        assertThat(new com.agilityhub.core.cli.CoreCli(List.of(command)).execute(
                new DefaultApplicationArguments("--core.command=migration:welcome", "--club=sample"))).isEqualTo(1);
    }
    @Test void T_18_09_invalidOptionsFailBeforeAnyAccountAccess() {
        var bad = List.of(new String[]{}, new String[]{"sample"}, new String[]{"--club"}, new String[]{"--club="},
                new String[]{"--club=a","--club=b"}, new String[]{"--club=a","--unknown"}, new String[]{"--club=a","--dry-run=true"},
                new String[]{"--club=a","--batch=0"}, new String[]{"--club=a","--batch=51"}, new String[]{"--club=a","--batch=no"},
                new String[]{"--club=a","--interval=0s"}, new String[]{"--club=a","--interval=61m"},
                new String[]{"--club=a","--interval=99999999999999999999999s"}, new String[]{"--club=a","--only-numbers=0"},
                new String[]{"--club=a","--only-numbers=1,"});
        for (var args : bad) {
            assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(clubs, recipients, delivery, pause);
    }
    @Test void T_18_09_alreadySentRecipientsDoNotCauseBatchWaits() {
        when(clubs.resolve("sample")).thenReturn("club");
        when(recipients.list(Set.of())).thenReturn(List.of(recipient(1,"a"), recipient(2,"b")));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(MigrationWelcomeDelivery.Result.ALREADY_SENT);
        command.run(new DefaultApplicationArguments("--club=sample", "--batch=1"));
        verifyNoInteractions(pause);
    }
    @Test void T_18_09_defaultBatchStopsAtFiftyWhenTheOperatorInterruptsTheWait() {
        when(clubs.resolve("sample")).thenReturn("club");
        when(recipients.list(Set.of())).thenReturn(java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(number -> recipient(number, "account-" + number)).toList());
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(MigrationWelcomeDelivery.Result.SENT);
        var interrupted = new MigrationWelcomeCommand(clubs, recipients, delivery);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> interrupted.run(new DefaultApplicationArguments("--club=sample")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
            verify(delivery, times(50)).deliver(anyString(), anyString(), anyString(), anyString(), eq(false));
            verify(delivery, never()).deliver(eq("member-51"), anyString(), anyString(), anyString(), eq(false));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(TenantContext.current()).isNull();
        } finally { Thread.interrupted(); }
    }
}
