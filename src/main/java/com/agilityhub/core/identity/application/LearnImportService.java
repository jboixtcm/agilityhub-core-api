package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Email;
import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.identity.application.LearnImportReport.Outcome.*;
import static com.agilityhub.core.identity.application.LearnImportReport.Reason.*;

@Service
public class LearnImportService {
    private static final List<String> HEADER = List.of("id", "email", "password", "name", "role", "created_at");
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final PlatformRoleService roles;
    private final IdentityTransactions transactions;
    private final EventPublisher events;
    private final Clock clock;
    public LearnImportService(AccountRepository accounts, AccountService accountService, PlatformRoleService roles,
                              IdentityTransactions transactions, EventPublisher events, Clock clock) {
        this.accounts = accounts; this.accountService = accountService; this.roles = roles;
        this.transactions = transactions; this.events = events; this.clock = clock;
    }
    public LearnImportReport importFile(Path file, boolean dryRun, Set<String> platformAdmins) {
        List<List<String>> csv;
        try { csv = LearnCsv.read(Files.readString(file)); }
        catch (IOException failure) { return failure(dryRun, INPUT_UNREADABLE); }
        catch (IllegalArgumentException failure) { return failure(dryRun, INVALID_CSV); }
        if (csv.isEmpty() || !csv.getFirst().equals(HEADER)) { return failure(dryRun, INVALID_COLUMNS); }
        var admins = platformAdmins.stream().map(Email::normalize).collect(java.util.stream.Collectors.toSet());
        return dryRun ? process(csv, true, admins) : transactions.run(() -> process(csv, false, admins));
    }
    private LearnImportReport failure(boolean dryRun, LearnImportReport.Reason reason) {
        return LearnImportReport.of(dryRun, List.of(new LearnImportReport.Entry(0, ERROR, reason)));
    }
    private LearnImportReport process(List<List<String>> csv, boolean dryRun, Set<String> admins) {
        var results = new ArrayList<LearnImportReport.Entry>();
        // Preview caches model earlier rows exactly, including duplicate ids/emails in the same file.
        var byEmail = new HashMap<String, Account>();
        var byLearnId = new HashMap<String, Account>();
        for (int index = 1; index < csv.size(); index++) {
            int ordinal = index + 1;
            var fields = csv.get(index);
            var reason = validate(fields);
            if (reason != null) {
                results.add(new LearnImportReport.Entry(ordinal, reason == GUEST ? SKIPPED : ERROR, reason)); continue;
            }
            var id = fields.get(0); var email = Email.normalize(fields.get(1));
            var knownId = byLearnId.computeIfAbsent(id, key -> accounts.findByLearnUserId(key).orElse(null));
            var existing = byEmail.computeIfAbsent(email, key -> accounts.findByEmail(key).orElse(null));
            if (knownId != null && !knownId.email().equals(email)) {
                results.add(new LearnImportReport.Entry(ordinal, ERROR, LEARN_ID_CONFLICT)); continue;
            }
            if (existing != null && existing.externalIds().containsKey("learnUserId") && !id.equals(existing.externalIds().get("learnUserId"))) {
                results.add(new LearnImportReport.Entry(ordinal, ERROR, EMAIL_CONFLICT)); continue;
            }
            if (existing != null && existing.status() != Account.Status.ACTIVE) {
                results.add(new LearnImportReport.Entry(ordinal, ERROR, ACCOUNT_NOT_ACTIVE)); continue;
            }
            boolean created = existing == null;
            boolean alreadyLinked = existing != null && id.equals(existing.externalIds().get("learnUserId"));
            boolean grant = admins.contains(email) && (created || !existing.platformRoles().contains(Account.PlatformRole.AGILITYHUB_ADMIN));
            if (alreadyLinked && !grant) {
                results.add(new LearnImportReport.Entry(ordinal, SKIPPED, ALREADY_IMPORTED)); continue;
            }
            reason = created ? NEW_ACCOUNT : alreadyLinked ? PLATFORM_ADMIN_GRANTED
                    : existing.passwordHash() == null ? PASSWORD_ADOPTED : PASSWORD_PRESERVED;
            Account account;
            if (dryRun) { account = preview(existing, fields, email, grant); }
            else {
                account = created ? accountService.getOrCreate(email, fields.get(3), "es", Account.Source.IMPORT_LEARN, fields.get(2), true) : existing;
                if (!alreadyLinked) { accounts.linkLearn(account.id(), id, fields.get(4), fields.get(2), created ? createdAt(fields.get(5)) : null, clock.instant()); }
                if (grant) { roles.grant(account.id()); }
                account = accounts.findById(account.id()).orElseThrow();
            }
            byEmail.put(email, account); byLearnId.put(id, account);
            results.add(new LearnImportReport.Entry(ordinal, created ? CREATED : LINKED, reason));
        }
        var report = LearnImportReport.of(dryRun, results);
        if (!dryRun && report.created() + report.linked() > 0) {
            events.publish(new IdentityEvent(IdentityEvent.Kind.LearnAccountsImported, null, UUID.randomUUID().toString(), clock.instant(),
                    Map.of("created", report.created(), "merged", report.linked(), "errors", report.errors())));
        }
        return report;
    }
    private Account preview(Account existing, List<String> fields, String email, boolean grant) {
        var externalIds = new HashMap<>(existing == null ? Map.<String, String>of() : existing.externalIds());
        externalIds.put("learnUserId", fields.get(0)); externalIds.put("learnRole", fields.get(4));
        return new Account(existing == null ? fields.getFirst() : existing.id(), email, fields.get(3), "es",
                existing == null || existing.passwordHash() == null ? fields.get(2) : existing.passwordHash(),
                grant ? Set.of(Account.PlatformRole.AGILITYHUB_ADMIN) : existing == null ? Set.of() : existing.platformRoles(),
                Account.Status.ACTIVE, null, externalIds, true, createdAt(fields.get(5)));
    }
    private LearnImportReport.Reason validate(List<String> fields) {
        if (fields.size() != HEADER.size()) { return INVALID_COLUMNS; }
        if ("anonymous".equals(fields.get(4)) || Email.normalize(fields.get(1)).startsWith("anonymous@")) { return GUEST; }
        if (!fields.get(0).matches("[1-9][0-9]*")) { return INVALID_ID; }
        if (!validEmail(Email.normalize(fields.get(1)))) { return INVALID_EMAIL; }
        if (!fields.get(2).matches("\\$2y\\$12\\$[./A-Za-z0-9]{53}")) { return INVALID_PASSWORD; }
        if (fields.get(3).isBlank() || fields.get(3).length() > 200) { return INVALID_NAME; }
        if (!Set.of("user", "admin", "coach", "designer").contains(fields.get(4))) { return INVALID_ROLE; }
        try { createdAt(fields.get(5)); }
        catch (DateTimeParseException failure) { return INVALID_CREATED_AT; }
        return null;
    }
    // Match the API's syntactic email contract without requiring a public TLD (fixtures use example.test).
    static boolean validEmail(String email) { return email.length() <= 254 && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"); }
    /** Learn's timezone-free MySQL export is interpreted as UTC. Offset timestamps retain their instant. */
    private Instant createdAt(String value) {
        var normalized = value.replace(' ', 'T');
        try { return Instant.parse(normalized); }
        catch (DateTimeParseException noOffset) { return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC); }
    }
}
