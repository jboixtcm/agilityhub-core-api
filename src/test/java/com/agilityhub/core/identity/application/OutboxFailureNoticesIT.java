package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.application.OutboxFailureAlerts;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.shared.persistence.OutboxRepository;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * OUT-01 (owner decision of 13 Nov 2026), CATALEG_NOTIFICACIONS N-57: an outbox event that ran out of its retries e-mails every
 * active platform admin (not a blocked one, not a plain account), in their language, platform-branded, with the event's type,
 * its club and the error type, never its payload; at most once per admin, event type and UTC day.
 */
class OutboxFailureNoticesIT extends AbstractIntegrationTest {
    static final String CLUB = "out01-club";
    @Autowired MongoTemplate mongo; @Autowired AccountRepository accounts; @Autowired ClubRepository clubs; @Autowired OutboxFailureNotices notices;
    @Autowired OutboxRepository records; @Autowired EventPublisher publisher; @Autowired MongoTransactionManager manager; @Autowired ObjectMapper mapper;
    @Autowired EmailSender email;

    FakeEmailSender mailbox() { return (FakeEmailSender) email; }

    @BeforeEach void seed() {
        wipeDatabaseKeepingBootstrap();
        mailbox().clear();
        clock.setInstant(Instant.parse("2030-01-01T10:00:00Z"));
        clubs.save(PlatformFixtures.club(CLUB, "out01.example.test"));
        account("out01-admin-ca", "out01.admin.ca@example.test", "ca", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE);
        account("out01-admin-en", "out01.admin.en@example.test", "en", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE);
        account("out01-admin-blocked", "out01.admin.blocked@example.test", "ca", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.BLOCKED);
        account("out01-member", "out01.member@example.test", "ca", Set.of(), Account.Status.ACTIVE);
    }
    void account(String id, String address, String locale, Set<Account.PlatformRole> roles, Account.Status status) {
        accounts.save(new Account(id, address, "Fictional " + id, locale, null, roles, status, new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
    }
    List<Document> sent() { return mongo.find(Query.query(Criteria.where("code").is("N-57")), Document.class, "notifications"); }

    @Test void OUT_01_anEventThatRunsOutOfItsRetriesEmailsEveryActivePlatformAdminOncePerTypeAndDay() {
        var transactions = new TransactionTemplate(manager);
        String id = transactions.execute(status -> publisher.publish(new ClubConfigChanged(CLUB, clock.instant(), Map.of("diff", Map.of("name", "Fictional Club")),
                "account-a", null, DomainEvent.Origin.BACKOFFICE)));
        DomainEventHandler<ClubConfigChanged> failing = new DomainEventHandler<>() {
            public String eventType() { return "ClubUpdated"; }
            public Class<ClubConfigChanged> eventClass() { return ClubConfigChanged.class; }
            public void handle(String eventId, ClubConfigChanged event) { throw new IllegalArgumentException("fictional consumer bug with a private value"); }
        };
        var dispatcher = new OutboxDispatcher(records, Map.of("failing", failing), transactions, mapper, clock, new SimpleMeterRegistry(), 2, () -> List.<OutboxFailureAlerts>of(notices));
        dispatcher.dispatch();
        assertThat(sent()).isEmpty();
        clock.advance(Duration.ofSeconds(1)); dispatcher.dispatch();
        assertThat(mongo.findById(id, DomainEventRecord.class).status()).isEqualTo(DomainEventRecord.Status.FAILED);
        // One e-mail per active platform admin, in the account's language, platform-branded, no club tenant.
        assertThat(sent()).extracting(row -> row.get("recipient", Document.class).getString("accountId")).containsExactlyInAnyOrder("out01-admin-ca", "out01-admin-en");
        assertThat(sent()).allSatisfy(row -> { assertThat(row.get("clubId")).isNull(); assertThat(row.getString("status")).isIn("SENT", "DELIVERED"); });
        var catalan = mailbox().lastTo("out01.admin.ca@example.test");
        assertThat(catalan.subject()).isEqualTo("Esdeveniment intern no processat");
        assertThat(catalan.text()).contains("ClubUpdated", PlatformFixtures.club(CLUB, "out01.example.test").name(), "IllegalStateException", "FAILED")
                .doesNotContain("Fictional Club", "private value");
        assertThat(mailbox().lastTo("out01.admin.en@example.test").subject()).isEqualTo("Internal event not processed");
        assertThat(mailbox().messages()).noneMatch(m -> m.to().equals("out01.admin.blocked@example.test") || m.to().equals("out01.member@example.test"));

        // Another event of the same type the same UTC day: no second e-mail; another type, or the next day: one more each.
        var again = new OutboxFailureAlerts.FailedEvent("other-id", CLUB, "ClubUpdated", "Club", 36, "IllegalStateException", clock.instant());
        notices.failed(again);
        assertThat(sent()).hasSize(2);
        notices.failed(new OutboxFailureAlerts.FailedEvent("third-id", null, "AccountCreated", "Account", 36, "IllegalStateException", clock.instant()));
        assertThat(sent()).hasSize(4);
        assertThat(mailbox().lastTo("out01.admin.en@example.test").text()).contains("AccountCreated", "AgilityHub");
        notices.failed(new OutboxFailureAlerts.FailedEvent("fourth-id", CLUB, "ClubUpdated", "Club", 36, "IllegalStateException", clock.instant().plus(Duration.ofDays(1))));
        assertThat(sent()).hasSize(6);
    }
}
