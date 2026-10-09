package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static com.agilityhub.core.identity.application.LearnImportReport.Outcome.*;
import static com.agilityhub.core.identity.application.LearnImportReport.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link LearnImportService} (S01 R-01-12, T-01-14): report rows carry the CSV line number (the header
 * is line 1), a Learn id already linked to another email is a conflict, an import that only links existing accounts still
 * publishes `LearnAccountsImported`, a preview of a duplicate platform-admin row matches the real import (the second row is
 * already imported), and the 200-character name / 254-character email limits are inclusive. Collaborators are mocks; fictional
 * data and fictional bcrypt-shaped hashes.
 */
class LearnImportServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant ACCOUNT_CREATED_AT = Instant.parse("2026-02-01T09:00:00Z");
    static final String HEADER = "id,email,password,name,role,created_at\n";
    static final String HASH = "$2y$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";

    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountService accountService = mock(AccountService.class);
    final PlatformRoleService roles = mock(PlatformRoleService.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final EventPublisher events = mock(EventPublisher.class);
    final LearnImportService service = new LearnImportService(accounts, accountService, roles, transactions, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @TempDir Path folder;

    @BeforeEach void setUp() {
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        when(accounts.findByLearnUserId(any())).thenReturn(Optional.empty());
        when(accounts.findByEmail(any())).thenReturn(Optional.empty());
    }

    private Path csv(String rows) throws Exception {
        var file = folder.resolve("learn.csv");
        Files.writeString(file, HEADER + rows);
        return file;
    }

    static String row(String id, String email, String name) {
        return id + "," + email + "," + HASH + "," + name + ",user,2024-03-01 10:00:00\n";
    }

    /** A club signup account (SignupIdentityService.java:26-27: no password, Security(0, null, null, 0)) never linked to Learn. */
    static Account signupAccount() {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, ACCOUNT_CREATED_AT, null, null, Account.Source.SIGNUP, null, null);
    }

    /**
     * The same account after AccountRepository.linkLearn (AccountRepository.java:25-35): Learn id and role, onboarding pending, the
     * adopted Learn hash with its change date.
     */
    static Account linkedAccount() {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", HASH, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, NOW, 0), Map.of("learnUserId", "7", "learnRole", "user"), true, ACCOUNT_CREATED_AT, null, null,
                Account.Source.SIGNUP, null, null);
    }

    /**
     * An earlier import of Learn user 7 under her previous address (AccountService.java:37-40 with IMPORT_LEARN, then
     * AccountRepository.java:25-35); Learn now exports the same id with a new email.
     */
    static Account importedUnderAnOldEmail() {
        return new Account("acc-1", "laura.old@example.test", "Laura Example", "es", HASH, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, ACCOUNT_CREATED_AT, 0), Map.of("learnUserId", "7", "learnRole", "user"), true, ACCOUNT_CREATED_AT, null, null,
                Account.Source.IMPORT_LEARN, null, null);
    }

    @Test void T_01_14_theFirstDataRowIsReportedAsLine2() throws Exception {
        var report = service.importFile(csv(row("7", "laura@example.test", "Laura Example")), true, Set.of());

        assertThat(report.rows()).containsExactly(new LearnImportReport.Entry(2, CREATED, NEW_ACCOUNT));
    }

    @Test void T_01_14_aLearnIdAlreadyLinkedToAnotherEmailIsAConflict() throws Exception {
        when(accounts.findByLearnUserId("7")).thenReturn(Optional.of(importedUnderAnOldEmail()));

        var report = service.importFile(csv(row("7", "laura@example.test", "Laura Example")), true, Set.of());

        assertThat(report.rows()).containsExactly(new LearnImportReport.Entry(2, ERROR, LEARN_ID_CONFLICT));
    }

    @Test void T_01_14_anImportThatOnlyLinksExistingAccountsPublishesLearnAccountsImported() throws Exception {
        when(accounts.findByEmail("laura@example.test")).thenReturn(Optional.of(signupAccount()));
        when(accounts.findById("acc-1")).thenReturn(Optional.of(linkedAccount()));

        var report = service.importFile(csv(row("7", "laura@example.test", "Laura Example")), false, Set.of());

        assertThat(report.rows()).containsExactly(new LearnImportReport.Entry(2, LINKED, PASSWORD_ADOPTED));
        var event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(event.capture());
        assertThat(((IdentityEvent) event.getValue()).kind()).isEqualTo(IdentityEvent.Kind.LearnAccountsImported);
        assertThat(event.getValue().payload()).containsEntry("created", 0L).containsEntry("merged", 1L);
    }

    @Test void T_01_14_aPreviewOfADuplicatePlatformAdminRowFindsTheSecondAlreadyImported() throws Exception {
        var file = csv(row("7", "laura@example.test", "Laura Example") + row("7", "laura@example.test", "Laura Example"));

        var report = service.importFile(file, true, Set.of("laura@example.test"));

        assertThat(report.rows()).containsExactly(new LearnImportReport.Entry(2, CREATED, NEW_ACCOUNT),
                new LearnImportReport.Entry(3, SKIPPED, ALREADY_IMPORTED));
    }

    @Test void T_01_14_aNameOf200CharactersIsValid() throws Exception {
        var report = service.importFile(csv(row("7", "laura@example.test", "L".repeat(200))), true, Set.of());

        assertThat(report.rows()).containsExactly(new LearnImportReport.Entry(2, CREATED, NEW_ACCOUNT));
    }

    /** RFC-shaped addresses: a 64-character local part and DNS labels of at most 63 characters (254 = 64 + 1 + 189). */
    @Test void T_01_14_anEmailOf254CharactersIsValidAndOneOf255IsNot() {
        String local = "l".repeat(64);
        String labels = "a".repeat(63) + "." + "b".repeat(63) + ".";
        assertThat(LearnImportService.validEmail(local + "@" + labels + "c".repeat(56) + ".test")).isTrue();
        assertThat(LearnImportService.validEmail(local + "@" + labels + "c".repeat(57) + ".test")).isFalse();
    }
}
