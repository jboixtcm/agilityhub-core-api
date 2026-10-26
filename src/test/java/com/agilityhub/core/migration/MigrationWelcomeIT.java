package com.agilityhub.core.migration;

import com.agilityhub.core.clubs.census.application.MigrationWelcomeRecipients;
import com.agilityhub.core.clubs.messaging.application.EmailMessage;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.identity.application.MigrationWelcomeDelivery;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationWelcomeIT extends AbstractIntegrationTest {
    static final String CLUB = "welcome-review", OTHER = "welcome-review-other";
    @Autowired MongoTemplate mongo;
    @Autowired AccountRepository accounts;
    @Autowired MembershipRepository memberships;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired MigrationWelcomeRecipients recipients;
    @Autowired MigrationWelcomeDelivery delivery;
    @MockitoBean EmailSender mail;

    @BeforeEach void seed() {
        TenantContext.clear();
        for (String collection : List.of("members", "memberships", "notifications", "magic_link_tokens", "clubs", "parameters")) {
            mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("clubId").in(CLUB,OTHER), Criteria.where("_id").in(CLUB,OTHER))), collection);
        }
        mongo.remove(Query.query(Criteria.where("_id").regex("^welcome-review-")), Account.class);
        clubs.save(PlatformFixtures.club(CLUB, CLUB + ".example.test"));
        clubs.save(PlatformFixtures.club(OTHER, OTHER + ".example.test"));
        configs.invalidate(CLUB); configs.invalidate(OTHER);
        when(mail.send(any())).thenReturn(EmailSender.SendResult.sent("fictional-provider-id"));
    }
    @AfterEach void clear() { TenantContext.clear(); }
    String add(String tenant, String suffix, Integer number, String state) {
        String accountId = "welcome-review-" + suffix;
        accounts.save(new Account(accountId, suffix + "@example.test", "Fictional " + suffix, "ca", null,
                Set.of(), Account.Status.ACTIVE, null, Map.of(), true, clock.instant(), null, null, Account.Source.MIGRATION, null, null));
        try (var scope = TenantContext.open(tenant)) {
            memberships.insert(new Membership("membership-" + accountId, accountId, tenant, "member-" + accountId,
                    Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER));
        }
        mongo.insert(new Document("_id", "member-" + accountId).append("clubId", tenant).append("accountId", accountId)
                .append("memberNumber", number).append("status", state).append("externalIds", Map.of("playoff", suffix))
                .append("firstName", "Fictional").append("gender", "OTHER"), "members");
        return accountId;
    }
    MigrationWelcomeDelivery.Result send(String id, boolean dry) {
        return delivery.deliver("member-" + id, id, "Fictional", "OTHER", dry);
    }
    @Test void T_18_09_T_18_10_recipientsAreImportedActiveAndTenantScopedIncludingNumberConflicts() {
        String active = add(CLUB,"active",1,"ACTIVE"), unnumbered = add(CLUB,"unnumbered",null,"ACTIVE");
        add(CLUB,"left",2,"LEFT"); String other = add(OTHER,"other",1,"ACTIVE");
        try (var scope = TenantContext.open(CLUB)) {
            assertThat(recipients.list(Set.of())).extracting(MigrationWelcomeRecipients.Recipient::accountId)
                    .containsExactlyInAnyOrder(active,unnumbered);
            assertThat(recipients.list(Set.of(1))).extracting(MigrationWelcomeRecipients.Recipient::accountId).containsExactly(active);
            assertThat(send(other,false)).isEqualTo(MigrationWelcomeDelivery.Result.INELIGIBLE);
            assertThat(send(active,true)).isEqualTo(MigrationWelcomeDelivery.Result.ELIGIBLE);
            assertThat(accounts.migrationWelcome(active).deliveryId()).isNull();
        }
        verifyNoInteractions(mail);
    }
    @Test void T_18_09_welcomeIsLocalizedSevenDaysAndResumableWithoutAnotherTokenOrEmail() {
        String id = add(CLUB,"accepted",1,"ACTIVE");
        try (var scope = TenantContext.open(CLUB)) {
            assertThat(send(id,false)).isEqualTo(MigrationWelcomeDelivery.Result.SENT);
            assertThat(send(id,false)).isEqualTo(MigrationWelcomeDelivery.Result.ALREADY_SENT);
            assertThat(accounts.migrationWelcome(id).sentAt()).isEqualTo(clock.instant());
            var token = mongo.findOne(Query.query(Criteria.where("accountId").is(id)), MagicLinkToken.class);
            assertThat(token).isNotNull();
            assertThat(token.purpose()).isEqualTo(MagicLinkToken.Purpose.WELCOME);
            assertThat(Duration.between(token.createdAt(),token.expiresAt())).isEqualTo(Duration.ofDays(7));
            assertThat(token.clientId()).isEqualTo("clubs-app");
            // A crash after provider status persistence, before the account marker: reconcile without resending.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().unset("welcomeSentAt"), Account.class);
            assertThat(send(id,true)).isEqualTo(MigrationWelcomeDelivery.Result.ALREADY_SENT);
            assertThat(accounts.migrationWelcome(id).sentAt()).isNull();
            assertThat(send(id,false)).isEqualTo(MigrationWelcomeDelivery.Result.ALREADY_SENT);
            assertThat(accounts.migrationWelcome(id).sentAt()).isEqualTo(clock.instant());
            accounts.patch(id,"es",null);
            assertThat(accounts.migrationWelcome(id).sentAt()).isEqualTo(clock.instant());
            assertThat(mongo.count(Query.query(Criteria.where("accountId").is(id)),MagicLinkToken.class)).isEqualTo(1);
        }
        var email = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mail).send(email.capture());
        assertThat(email.getValue().subject()).contains("nova app");
        assertThat(email.getValue().text()).contains("Fictional").contains("/activacio?t=");
    }
    @Test void T_18_09_unknownOrFailedAcceptanceNeverAutomaticallyResends() {
        String uncertain = add(CLUB,"uncertain",1,"ACTIVE"), failed = add(CLUB,"failed",2,"ACTIVE");
        try (var scope = TenantContext.open(CLUB)) {
            accounts.claimMigrationWelcome(uncertain,"unconfirmed-delivery",clock.instant());
            assertThat(send(uncertain,false)).isEqualTo(MigrationWelcomeDelivery.Result.REVIEW_REQUIRED);
            when(mail.send(any())).thenReturn(EmailSender.SendResult.failed("fixture refusal"));
            assertThat(send(failed,false)).isEqualTo(MigrationWelcomeDelivery.Result.REVIEW_REQUIRED);
            assertThat(send(failed,false)).isEqualTo(MigrationWelcomeDelivery.Result.REVIEW_REQUIRED);
            assertThat(accounts.migrationWelcome(failed).sentAt()).isNull();
        }
        verify(mail,times(1)).send(any());
    }
    @Test void T_18_09_concurrentCommandsClaimOnlyOneProviderDelivery() throws Exception {
        String id = add(CLUB,"concurrent",1,"ACTIVE");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<MigrationWelcomeDelivery.Result> task = () -> {
                start.await();
                try (var scope = TenantContext.open(CLUB)) { return send(id,false); }
            };
            var first = executor.submit(task); var second = executor.submit(task); start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)))
                    .filteredOn(result -> result == MigrationWelcomeDelivery.Result.SENT).hasSize(1);
        }
        verify(mail,times(1)).send(any());
    }
}
