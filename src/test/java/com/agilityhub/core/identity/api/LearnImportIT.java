package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.*;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static com.agilityhub.core.identity.application.LearnImportReport.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearnImportIT extends IdentityIntegrationSupport {
    static final String HEADER = "id,email,password,name,role,created_at\n";
    static final String FIXTURE_PASSWORD = "Fictional-Learn-password";
    static final Instant CREATED = Instant.parse("2023-04-05T04:07:08Z");
    static List<String> hashes;
    static String fixture;
    @Autowired LearnImportService importer;
    @Autowired IdentityTransactions transactions;
    @Autowired AccountService accountService;
    @TempDir Path directory;

    @BeforeAll static void generateFixture() {
        var encoder = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2Y, 12);
        hashes = new ArrayList<>();
        var csv = new StringBuilder(HEADER);
        for (int n = 1; n <= 50; n++) {
            hashes.add(encoder.encode(FIXTURE_PASSWORD));
            csv.append(row(n, "learn" + n + "@example.test", hashes.getLast(), n == 1 ? "admin" : "user"));
        }
        fixture = csv.toString();
    }
    /** Reproduce the committed smoke fixture using only fictional data and test-generated bcrypt hashes. */
    public static void main(String[] args) throws Exception { generateFixture(); Files.writeString(Path.of(args[0]), fixture); }
    static String row(int id, String email, String hash, String role) {
        return id + "," + email + "," + hash + ",\"Example, \"\"Learner\"\" " + id + "\"," + role + ",2023-04-05 06:07:08\n";
    }
    @BeforeEach void emptyImportCollections() {
        for (String collection : List.of("accounts", "memberships", "domain_events", "audit_entries")) { mongo.remove(new Query(), collection); }
    }
    Path file(String contents) throws Exception { return Files.writeString(directory.resolve("learn.csv"), contents); }
    List<org.bson.Document> documents(String collection) { return mongo.findAll(org.bson.Document.class, collection); }
    LearnImportReport run(Path file, boolean dryRun) { return importer.importFile(file, dryRun, Set.of()); }

    @Test void T_01_14_fiftyPhpBcryptAccountsLoginAndRerunWithoutMutations() throws Exception {
        var input = file(fixture);
        assertThat(run(input, true).created()).isEqualTo(50);
        for (String collection : List.of("accounts", "memberships", "domain_events", "audit_entries")) { assertThat(documents(collection)).isEmpty(); }
        var first = run(input, false);
        assertThat(first.created()).isEqualTo(50); assertThat(first.errors()).isZero();
        assertThat(documents("accounts")).hasSize(50); assertThat(documents("memberships")).isEmpty();
        for (int n = 1; n <= 50; n++) {
            var account = accounts.findByLearnUserId("" + n).orElseThrow();
            assertThat(account.passwordHash()).isEqualTo(hashes.get(n - 1));
            assertThat(account.locale()).isEqualTo("es"); assertThat(account.name()).isEqualTo("Example, \"Learner\" " + n);
            assertThat(account.createdSource()).isEqualTo(Account.Source.IMPORT_LEARN); assertThat(account.createdAt()).isEqualTo(CREATED);
            assertThat(account.onboardingPending()).isTrue(); assertThat(account.status()).isEqualTo(Account.Status.ACTIVE);
            assertThat(account.externalIds()).containsEntry("learnRole", n == 1 ? "admin" : "user");
            assertThat(account.platformRoles()).isEmpty(); assertThat(account.emailVerifiedAt()).isNull(); assertThat(account.consents()).isEmpty();
        }
        var response = mvc.perform(post("/oauth2/token").header("Host", "id.agilitydoghub.com").contentType("application/x-www-form-urlencoded")
                .param("grant_type", "password").param("client_id", "id-web").param("username", "learn1@example.test").param("password", FIXTURE_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").isNotEmpty()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(hashes.getFirst());
        var account = accounts.findByLearnUserId("1").orElseThrow();
        assertThat(account.passwordHash()).isEqualTo(hashes.getFirst());
        accounts.completeOnboarding(account.id(), null);
        var before = documents("accounts"); var events = documents("domain_events");
        var second = run(input, false);
        assertThat(second.created()).isZero(); assertThat(second.linked()).isZero(); assertThat(second.skipped()).isEqualTo(50);
        assertThat(documents("accounts")).isEqualTo(before); assertThat(documents("domain_events")).isEqualTo(events);
        assertThat(events.stream().filter(event -> "LearnAccountsImported".equals(event.getString("type")))).hasSize(1);
        String report = mapper.writeValueAsString(first) + first.render();
        assertThat(report).doesNotContain("@", "Example", "$2y$", FIXTURE_PASSWORD, "learnUserId");
    }
    @Test void T_01_14_exportTimestampsUseMadridSeasonalOffsetsAndPreserveExplicitOffsets() throws Exception {
        var values = List.of("2023-01-05 06:07:08", "2023-07-05 06:07:08", "2023-04-05T06:07:08Z", "2023-04-05T06:07:08+03:00");
        var expected = List.of("2023-01-05T05:07:08Z", "2023-07-05T04:07:08Z", "2023-04-05T06:07:08Z", "2023-04-05T03:07:08Z");
        for (int n = 0; n < values.size(); n++) {
            var input = file(HEADER + row(n + 1, "time" + n + "@example.test", hashes.get(n), "user")
                    .replace("2023-04-05 06:07:08", values.get(n)));
            assertThat(run(input, true).created()).isEqualTo(1);
            assertThat(run(input, false).created()).isEqualTo(1);
            assertThat(accounts.findByLearnUserId("" + (n + 1)).orElseThrow().createdAt()).isEqualTo(Instant.parse(expected.get(n)));
        }
    }
    @Test void T_01_14_existingAccountsKeepProfileSecurityAndOtherExternalIdsAndOnlyAdoptMissingHash() throws Exception {
        var existing = accountService.getOrCreate("kept@example.test", "Existing Name", "ca", Account.Source.CONSOLE, passwords.hash(PASSWORD), false);
        accounts.save(new Account("missing", "missing@example.test", "Passwordless", "en", null, Set.of(), Account.Status.ACTIVE,
                null, Map.of("anotherSystem", "fictional"), false, clock.instant()));
        var input = file(HEADER + row(1, "KEPT@example.test", hashes.getFirst(), "coach") + row(2, "missing@example.test", hashes.get(1), "designer"));
        var snapshot = documents("accounts");
        assertThat(run(input, true).linked()).isEqualTo(2); assertThat(documents("accounts")).isEqualTo(snapshot);
        var report = run(input, false);
        assertThat(report.linked()).isEqualTo(2); assertThat(report.rows()).extracting(LearnImportReport.Entry::reason)
                .containsExactly(PASSWORD_PRESERVED, PASSWORD_ADOPTED);
        var kept = accounts.findById(existing.id()).orElseThrow();
        assertThat(kept.passwordHash()).isEqualTo(existing.passwordHash()); assertThat(kept.security()).isEqualTo(existing.security());
        assertThat(kept.name()).isEqualTo(existing.name()); assertThat(kept.locale()).isEqualTo(existing.locale());
        assertThat(kept.createdSource()).isEqualTo(existing.createdSource()); assertThat(kept.createdAt()).isEqualTo(existing.createdAt());
        assertThat(kept.onboardingPending()).isTrue();
        var adopted = accounts.findById("missing").orElseThrow();
        assertThat(adopted.passwordHash()).isEqualTo(hashes.get(1)); assertThat(adopted.externalIds()).containsEntry("anotherSystem", "fictional");
        assertThat(adopted.security().passwordChangedAt()).isEqualTo(clock.instant()); assertThat(adopted.familyVersion()).isZero();
    }
    @Test void T_01_14_guestExcludedAndOnlyExplicitAllowlistGrantsAuditedPlatformAdmin() throws Exception {
        var input = file(HEADER + row(1, "one@example.test", hashes.getFirst(), "admin")
                + row(2, "two@example.test", hashes.get(1), "user") + row(3, "anonymous@example.test", "", "user")
                + row(4, "guest@example.test", "", "anonymous"));
        var preview = importer.importFile(input, true, Set.of("TWO@example.test"));
        assertThat(preview.created()).isEqualTo(2); assertThat(preview.skipped()).isEqualTo(2); assertThat(documents("audit_entries")).isEmpty();
        var report = importer.importFile(input, false, Set.of("TWO@example.test"));
        assertThat(report.rows()).isEqualTo(preview.rows()); assertThat(accounts.activePlatformAdmins()).isEqualTo(1);
        assertThat(accounts.findByLearnUserId("1").orElseThrow().platformRoles()).isEmpty();
        assertThat(accounts.findByLearnUserId("2").orElseThrow().platformRoles()).containsExactly(Account.PlatformRole.AGILITYHUB_ADMIN);
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(1);
        assertThat(importer.importFile(input, true, Set.of("one@example.test")).linked()).isEqualTo(1);
        assertThat(importer.importFile(input, false, Set.of("one@example.test")).rows().getFirst().reason()).isEqualTo(PLATFORM_ADMIN_GRANTED);
        assertThat(importer.importFile(input, false, Set.of("one@example.test", "two@example.test")).linked()).isZero();
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(2);
    }
    @Test void T_01_14_duplicateRowsAndConflictingIdsMatchPreviewAndNeverRelinkAccounts() throws Exception {
        var first = row(1, "one@example.test", hashes.getFirst(), "user");
        var input = file(HEADER + first + first + row(1, "different@example.test", hashes.getFirst(), "user")
                + row(2, "one@example.test", hashes.getFirst(), "user"));
        var preview = run(input, true); var report = run(input, false);
        assertThat(report.rows()).isEqualTo(preview.rows()); assertThat(report.created()).isEqualTo(1);
        assertThat(report.rows()).extracting(LearnImportReport.Entry::reason).containsExactly(NEW_ACCOUNT, ALREADY_IMPORTED, LEARN_ID_CONFLICT, EMAIL_CONFLICT);
        assertThat(documents("accounts")).hasSize(1);
        mongo.updateFirst(new Query(), new Update().set("status", "BLOCKED"), Account.class);
        assertThat(run(file(HEADER + first), false).rows().getFirst().reason()).isEqualTo(ACCOUNT_NOT_ACTIVE);
    }
    @Test void T_01_14_invalidRowsHaveSafeReasonsAndMalformedFileWritesNothing() throws Exception {
        var valid = row(1, "one@example.test", hashes.getFirst(), "user");
        var invalid = HEADER + "one,column\n" + valid.replace("1,one", "0,one") + valid.replace("one@example.test", "private-value")
                + valid.replace(hashes.getFirst(), "private-password") + valid.replace("Example, \"\"Learner\"\" 1", " ")
                + valid.replace(",user,", ",unknown,") + valid.replace("2023-04-05 06:07:08", "invalid-date");
        var report = run(file(invalid), false);
        assertThat(report.errors()).isEqualTo(7); assertThat(report.rows()).extracting(LearnImportReport.Entry::reason)
                .containsExactly(INVALID_COLUMNS, INVALID_ID, INVALID_EMAIL, INVALID_PASSWORD, INVALID_NAME, INVALID_ROLE, INVALID_CREATED_AT);
        assertThat(mapper.writeValueAsString(report) + report.render()).doesNotContain("private", "@", "invalid-date");
        assertThat(run(file(HEADER + valid + "\"unclosed"), false).rows().getFirst().reason()).isEqualTo(INVALID_CSV);
        assertThat(run(file("id,email,password,name,locale,created_at\n"), false).rows().getFirst().reason()).isEqualTo(INVALID_COLUMNS);
        assertThat(run(file(""), false).errors()).isEqualTo(1);
        assertThat(run(directory.resolve("absent"), false).rows().getFirst().reason()).isEqualTo(INPUT_UNREADABLE);
        assertThat(documents("accounts")).isEmpty(); assertThat(documents("domain_events")).isEmpty();
    }
    @Test void T_01_14_importAccountAuditAndOutboxRollbackTogether() throws Exception {
        var input = file(HEADER + row(1, "one@example.test", hashes.getFirst(), "user"));
        assertThatThrownBy(() -> transactions.run(() -> {
            importer.importFile(input, false, Set.of("one@example.test"));
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        for (String collection : List.of("accounts", "domain_events", "audit_entries")) { assertThat(documents(collection)).isEmpty(); }
    }
    @Test void T_01_14_concurrentImportCreatesOneAccountAndOneImportEvent() throws Exception {
        var input = file(HEADER + row(1, "one@example.test", hashes.getFirst(), "user"));
        var gate = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            Callable<LearnImportReport> work = () -> { gate.await(); return run(input, false); };
            var results = pool.invokeAll(List.of(work, work));
            assertThat(results.getFirst().get().created() + results.getLast().get().created()).isEqualTo(1);
        }
        assertThat(documents("accounts")).hasSize(1);
        assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(2);
        var first = accounts.findByLearnUserId("1").orElseThrow();
        assertThatThrownBy(() -> accounts.save(new Account("collision", "other@example.test", "Other", "es", null,
                Set.of(), Account.Status.ACTIVE, null, first.externalIds(), false, clock.instant())))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
