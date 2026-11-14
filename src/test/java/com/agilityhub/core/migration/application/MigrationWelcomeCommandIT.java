package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.MigrationWelcomeRecipients;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.identity.application.MigrationWelcomeDelivery;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MIG-08 (owner decision, 13-11-2026) over real Mongo: `migration:welcome` previews by default (who would receive it and the
 * counts) without a claim, a token, a notification or an e-mail; `--send` on the prod profile sends only after the club slug
 * is typed back. Fictional accounts and the test profile's fake sender only.
 */
class MigrationWelcomeCommandIT extends AbstractIntegrationTest {
    static final String CLUB = "welcome-command";
    @Autowired MongoTemplate mongo;
    @Autowired AccountRepository accounts;
    @Autowired MembershipRepository memberships;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired MigrationClubAccess clubAccess;
    @Autowired MigrationWelcomeRecipients recipients;
    @Autowired MigrationWelcomeDelivery delivery;
    @Autowired MigrationWelcomeCommand command;
    @Autowired EmailSender sender;

    @BeforeEach void seed() {
        TenantContext.clear();
        for (String collection : List.of("members", "memberships", "notifications", "magic_link_tokens", "clubs", "parameters")) {
            mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("clubId").is(CLUB), Criteria.where("_id").is(CLUB))), collection);
        }
        mongo.remove(Query.query(Criteria.where("_id").regex("^welcome-command-")), Account.class);
        clubs.save(PlatformFixtures.club(CLUB, CLUB + ".example.test"));
        configs.invalidate(CLUB);
        ((FakeEmailSender) sender).clear();
    }
    @AfterEach void clear() { TenantContext.clear(); }
    String add(String suffix, Integer number) {
        String accountId = "welcome-command-" + suffix;
        accounts.save(new Account(accountId, accountId + "@example.test", "Fictional " + suffix, "ca", null,
                Set.of(), Account.Status.ACTIVE, null, Map.of(), true, clock.instant(), null, null, Account.Source.MIGRATION, null, null));
        try (var scope = TenantContext.open(CLUB)) {
            memberships.insert(new Membership("membership-" + accountId, accountId, CLUB, "member-" + accountId,
                    Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER));
        }
        mongo.insert(new Document("_id", "member-" + accountId).append("clubId", CLUB).append("accountId", accountId)
                .append("memberNumber", number).append("status", "ACTIVE").append("externalIds", Map.of("playoff", suffix))
                .append("firstName", "Fictional").append("gender", "OTHER"), "members");
        return accountId;
    }
    String printed(MigrationWelcomeCommand target, String... args) {
        var out = new ByteArrayOutputStream(); var previous = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { target.run(new DefaultApplicationArguments(args)); } finally { System.setOut(previous); }
        return out.toString(StandardCharsets.UTF_8);
    }
    long mails() { return ((FakeEmailSender) sender).messages().stream().filter(m -> m.to().startsWith("welcome-command-")).count(); }
    void nothingWritten(String... ids) {
        for (String id : ids) {
            assertThat(accounts.migrationWelcome(id).deliveryId()).as(id).isNull();
            assertThat(accounts.migrationWelcome(id).sentAt()).as(id).isNull();
        }
        assertThat(mongo.count(Query.query(Criteria.where("accountId").in(List.of(ids))), MagicLinkToken.class)).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "notifications")).isZero();
        assertThat(mails()).isZero();
    }

    @Test void MIG_08_withoutSendTheCommandPreviewsWhoWouldReceiveItAndWritesOrSendsNothing() {
        String first = add("first", 1), second = add("second", 2);
        var output = printed(command, "--club=" + CLUB);
        assertThat(output).contains("WOULD SEND #1 Fictional", "WOULD SEND #2 Fictional", "DRY RUN {ELIGIBLE=2}")
                .doesNotContain("@example.test", "/activacio", "WELCOME {");
        nothingWritten(first, second);
    }

    @Test void MIG_08_onTheProdProfileASendWaitsForTheTypedClubSlug() {
        String first = add("first", 1), second = add("second", 2);
        var production = mock(Environment.class);
        when(production.matchesProfiles("prod", "production")).thenReturn(true);
        var refused = new MigrationWelcomeCommand(clubAccess, recipients, delivery, production, pause -> { }, () -> "another-club");
        assertThatThrownBy(() -> printed(refused, "--club=" + CLUB, "--send"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
        nothingWritten(first, second);
        var confirmed = new MigrationWelcomeCommand(clubAccess, recipients, delivery, production, pause -> { }, () -> CLUB);
        assertThat(printed(confirmed, "--club=" + CLUB, "--send")).contains("DRY RUN {ELIGIBLE=2}", "type the club slug to send 2 welcome e-mails", "WELCOME {SENT=2}");
        assertThat(accounts.migrationWelcome(first).sentAt()).isNotNull();
        assertThat(accounts.migrationWelcome(second).sentAt()).isNotNull();
        assertThat(mails()).isEqualTo(2);
        // A second preview finds both sent and would send nobody.
        assertThat(printed(command, "--club=" + CLUB)).doesNotContain("WOULD SEND").contains("DRY RUN {ALREADY_SENT=2}");
    }
}
