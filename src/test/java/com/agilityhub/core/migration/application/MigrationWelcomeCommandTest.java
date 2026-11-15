package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.MigrationWelcomeRecipients;
import com.agilityhub.core.identity.application.MigrationWelcomeDelivery;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationWelcomeCommandTest {
    final MigrationClubAccess clubs = mock(MigrationClubAccess.class);
    final MigrationWelcomeRecipients recipients = mock(MigrationWelcomeRecipients.class);
    final MigrationWelcomeDelivery delivery = mock(MigrationWelcomeDelivery.class);
    @SuppressWarnings("unchecked") final Consumer<Duration> pause = mock(Consumer.class);
    final Environment environment = mock(Environment.class);
    @SuppressWarnings("unchecked") final Supplier<String> typed = mock(Supplier.class);
    final MigrationWelcomeCommand command = new MigrationWelcomeCommand(clubs, recipients, delivery, environment, pause, typed);
    @AfterEach void clear() { TenantContext.clear(); }
    /** Runs the command and returns what it printed on stdout. */
    String printed(MigrationWelcomeCommand target, String... args) {
        var out = new ByteArrayOutputStream(); var previous = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { target.run(new DefaultApplicationArguments(args)); } finally { System.setOut(previous); }
        return out.toString(StandardCharsets.UTF_8);
    }
    void production() { when(environment.matchesProfiles("prod", "production")).thenReturn(true); }
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
        command.run(new DefaultApplicationArguments("--club=sample", "--send", "--batch=1", "--interval=2m"));
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
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--club=sample", "--send")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("requires review");
        assertThat(TenantContext.current()).isNull();
        assertThat(new com.agilityhub.core.cli.CoreCli(List.of(command)).execute(
                new DefaultApplicationArguments("--core.command=migration:welcome", "--club=sample", "--send"))).isEqualTo(1);
    }
    @Test void T_18_09_invalidOptionsFailBeforeAnyAccountAccess() {
        var bad = List.of(new String[]{}, new String[]{"sample"}, new String[]{"--club"}, new String[]{"--club="},
                new String[]{"--club=a","--club=b"}, new String[]{"--club=a","--unknown"}, new String[]{"--club=a","--dry-run=true"},
                new String[]{"--club=a","--batch=0"}, new String[]{"--club=a","--batch=51"}, new String[]{"--club=a","--batch=no"},
                new String[]{"--club=a","--interval=0s"}, new String[]{"--club=a","--interval=61m"},
                new String[]{"--club=a","--interval=99999999999999999999999s"}, new String[]{"--club=a","--only-numbers=0"},
                new String[]{"--club=a","--only-numbers=1,"}, new String[]{"--club=a","--send=true"}, new String[]{"--club=a","--send=yes","--send"},
                new String[]{"--club=a","--send","--dry-run"});
        for (var args : bad) {
            assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(clubs, recipients, delivery, pause, typed);
    }
    @Test void T_18_09_alreadySentRecipientsDoNotCauseBatchWaits() {
        when(clubs.resolve("sample")).thenReturn("club");
        when(recipients.list(Set.of())).thenReturn(List.of(recipient(1,"a"), recipient(2,"b")));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(MigrationWelcomeDelivery.Result.ALREADY_SENT);
        command.run(new DefaultApplicationArguments("--club=sample", "--send", "--batch=1"));
        verifyNoInteractions(pause);
    }
    @Test void T_18_09_defaultBatchStopsAtFiftyWhenTheOperatorInterruptsTheWait() {
        when(clubs.resolve("sample")).thenReturn("club");
        when(recipients.list(Set.of())).thenReturn(java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(number -> recipient(number, "account-" + number)).toList());
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(MigrationWelcomeDelivery.Result.SENT);
        var interrupted = new MigrationWelcomeCommand(clubs, recipients, delivery, environment);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> interrupted.run(new DefaultApplicationArguments("--club=sample", "--send")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
            verify(delivery, times(50)).deliver(anyString(), anyString(), anyString(), anyString(), eq(false));
            verify(delivery, never()).deliver(eq("member-51"), anyString(), anyString(), anyString(), eq(false));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(TenantContext.current()).isNull();
        } finally { Thread.interrupted(); }
    }
    /** MIG-08 (owner decision, 13-11-2026): with no flag the command previews — who would receive it, then the counts — and sends nothing. */
    @Test void MIG_08_withoutSendItPreviewsWhoWouldReceiveItAndTheCountsAndSendsNothing() {
        when(clubs.resolve("sample")).thenReturn("club");
        when(recipients.list(Set.of())).thenReturn(List.of(recipient(1, "a"), recipient(9, "staff"), recipient(2, "sent"),
                new MigrationWelcomeRecipients.Recipient("member-x", "x", null, "Unnumbered", "OTHER")));
        when(delivery.staff("staff")).thenReturn(true);
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(true))).thenReturn(MigrationWelcomeDelivery.Result.ELIGIBLE);
        when(delivery.deliver(eq("member-2"), eq("sent"), anyString(), anyString(), eq(true))).thenReturn(MigrationWelcomeDelivery.Result.ALREADY_SENT);
        for (var args : List.of(new String[]{"--club=sample"}, new String[]{"--club=sample", "--dry-run"})) {
            assertThat(printed(command, args)).isEqualTo(String.join(System.lineSeparator(), "WOULD SEND #9 Fictional (staff)", "WOULD SEND #1 Fictional",
                    "WOULD SEND #- Unnumbered", "DRY RUN {ELIGIBLE=3, ALREADY_SENT=1}", ""));
        }
        verify(delivery, never()).deliver(anyString(), anyString(), anyString(), anyString(), eq(false));
        verifyNoInteractions(pause, typed);
        // Not on the prod profile: --send sends at once, with no preview and no typed confirmation.
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(false))).thenReturn(MigrationWelcomeDelivery.Result.SENT);
        assertThat(printed(command, "--club=sample", "--send")).doesNotContain("WOULD SEND").contains("WELCOME {SENT=4}");
        verifyNoInteractions(typed);
    }
    /** MIG-08: on the prod profile --send previews and then sends only once the club slug is typed back. */
    @Test void MIG_08_onTheProdProfileASendNeedsTheClubSlugTypedBack() {
        production();
        when(clubs.resolve("sample")).thenReturn("club");
        when(recipients.list(Set.of())).thenReturn(List.of(recipient(1, "a"), recipient(2, "b")));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(true))).thenReturn(MigrationWelcomeDelivery.Result.ELIGIBLE);
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), eq(false))).thenReturn(MigrationWelcomeDelivery.Result.SENT);
        for (String answer : new String[]{null, "", "Sample", " sample", "other"}) {
            when(typed.get()).thenReturn(answer);
            assertThatThrownBy(() -> printed(command, "--club=sample", "--send"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
        }
        verify(delivery, never()).deliver(anyString(), anyString(), anyString(), anyString(), eq(false));
        assertThat(TenantContext.current()).isNull();
        when(typed.get()).thenReturn("sample");
        assertThat(printed(command, "--club=sample", "--send")).isEqualTo(String.join(System.lineSeparator(), "WOULD SEND #1 Fictional", "WOULD SEND #2 Fictional",
                "DRY RUN {ELIGIBLE=2}", "Production: type the club slug to send 2 welcome e-mails:", "WELCOME {SENT=2}", ""));
        verify(delivery, times(2)).deliver(anyString(), anyString(), anyString(), anyString(), eq(false));
        // A preview on the prod profile asks nothing.
        clearInvocations(typed);
        assertThat(printed(command, "--club=sample")).endsWith("DRY RUN {ELIGIBLE=2}" + System.lineSeparator());
        verifyNoInteractions(typed);
    }
}
