package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.identity.application.MagicLinkService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static com.agilityhub.core.clubs.census.application.CensusValues.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH-02 (owner decision 13-11-2026, S03 R-03-06b): an ADMIN changes a member's login e-mail from D10. The link goes to the NEW
 * address only; the change, the verification and the clearing of `emailStatus` happen when it is used, audited as
 * `ACCOUNT_EMAIL_CHANGED`; D10 shows the login e-mail and its suppression mark. Fictional people and addresses only.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class LoginEmailChangeIT extends AbstractIntegrationTest {
    static final String CLUB = "login-a", OTHER = "login-b", HOST = "login-a.example.test";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired CensusRepository<Member> members;
    @Autowired EmailSender emailSender;

    int number;
    FakeEmailSender mailbox() { return (FakeEmailSender) emailSender; }

    @BeforeEach void seed() {
        TenantContext.clear();
        mailbox().clear();
        number = 0;
        for (String collection : List.of("accounts", "memberships", "members", "audit_entries", "domain_events", "notifications",
                "magic_link_tokens", "refresh_tokens", "account_sessions", "security_events", "census_write_locks")) {
            mongo.remove(new Query(), collection);
        }
        for (String club : List.of(CLUB, OTHER)) {
            if (clubs.findById(club).isEmpty()) { clubs.save(PlatformFixtures.club(club, club + ".example.test")); }
            configs.invalidate(club);
        }
        hosts.invalidate();
        member(CLUB, "admin", "admin@example.test", "ACTIVE", Set.of(Role.MEMBER, Role.ADMIN), null);
        member(CLUB, "one", "old-one@example.test", "ACTIVE", Set.of(Role.MEMBER), NotificationAccounts.EmailStatus.BOUNCED);
        member(CLUB, "pending", "pending@example.test", "PENDING", Set.of(Role.MEMBER), null);
        member(CLUB, "shared", "shared@example.test", "ACTIVE", Set.of(Role.MEMBER), null);
        try (var tenant = TenantContext.open(OTHER)) {
            mongo.insert(new Membership("shared-membership-b", "shared-account", OTHER, "shared-b", Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER));
        }
        mongo.insert(new Account("taken-account", "taken@example.test", "Example Taken", "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
    }

    void member(String club, String id, String email, String status, Set<Role> roles, NotificationAccounts.EmailStatus emailStatus) {
        mongo.insert(new Account(id + "-account", email, "Example " + id, "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant(), emailStatus));
        mongo.insert(new Membership(id + "-membership", id + "-account", club, id, roles, Membership.Status.ACTIVE, Role.MEMBER));
        var member = new Member(); member.id = id; member.clubId = club; member.accountId = id + "-account"; member.memberNumber = ++number;
        member.idDocument = object("type", "PASSPORT", "number", "fictional-" + id);
        member.firstName = "Example"; member.lastName1 = id; member.gender = "OTHER"; member.birthDate = LocalDate.of(1990, 1, 1);
        member.contactEmails = List.of(object("email", email, "bounced", false));
        member.phones = List.of(object("prefix", "+34", "number", "600000001", "label", "Primary"));
        member.address = object("street", "1 Example Street", "postalCode", "08349", "city", "Example Town", "country", "ES");
        member.status = status; member.joinedAt = Instant.parse("2020-01-01T00:00:00Z"); member.bookingBlock = object("active", false);
        try (var tenant = TenantContext.open(club)) { members.insert(member); }
    }

    ResultActions change(String member, String email) throws Exception { return change(member, email, "ADMIN"); }
    ResultActions change(String member, String email, String role) throws Exception {
        return mvc.perform(post("/api/v1/members/" + member + "/login-email").header("Host", HOST).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", email)))
                .with(jwt().jwt(j -> j.subject("admin-account").claim("clubId", CLUB).claim("name", "Example Admin"))
                        .authorities(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    JsonNode member(String id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/members/" + id).header("Host", HOST)
                        .with(jwt().jwt(j -> j.subject("admin-account").claim("clubId", CLUB)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    String linkTo(String email) {
        var mail = mailbox().lastTo(email);
        var matcher = Pattern.compile("https://" + Pattern.quote(HOST) + "/activacio\\?t=([A-Za-z0-9_-]{43})&purpose=email_change").matcher(mail.text());
        assertThat(matcher.find()).as(mail.text()).isTrue();
        return matcher.group(1);
    }
    ResultActions redeem(String value) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", HOST).contentType("application/x-www-form-urlencoded")
                .param("grant_type", MagicLinkService.GRANT).param("client_id", "clubs-app").param("token", value));
    }
    Account account(String id) { return mongo.findById(id, Account.class); }
    List<AuditEntry> audit() { return mongo.find(Query.query(Criteria.where("action").is(AuditAction.ACCOUNT_EMAIL_CHANGED)), AuditEntry.class); }

    @Test @AuditCovers(AuditAction.ACCOUNT_EMAIL_CHANGED)
    void AUTH_02_theNewAddressConfirmsTheChangeWhichClearsTheSuppressionAndIsAuditedAsTheAdmins() throws Exception {
        var before = member("one");
        assertThat(before.at("/login/email").asText()).isEqualTo("old-one@example.test");
        assertThat(before.at("/login/emailStatus").asText()).isEqualTo("BOUNCED");

        change("one", "New-One@Example.test").andExpect(status().isAccepted()).andExpect(jsonPath("$.sentTo").value("new-one@example.test"));
        // Nothing changes until the link is used, and nothing goes to the old address.
        assertThat(account("one-account").email()).isEqualTo("old-one@example.test");
        assertThat(account("one-account").emailStatus()).isEqualTo(NotificationAccounts.EmailStatus.BOUNCED);
        assertThat(audit()).isEmpty();
        assertThat(mailbox().messages()).extracting(message -> message.to()).containsExactly("new-one@example.test");
        var mail = mailbox().lastTo("new-one@example.test");
        assertThat(mail.subject()).contains("sign-in address");
        String value = linkTo("new-one@example.test");

        var tokens = mapper.readTree(redeem(value).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(tokens.path("access_token").asText()).isNotBlank();
        var changed = account("one-account");
        assertThat(changed.email()).isEqualTo("new-one@example.test");
        assertThat(changed.emailStatus()).isNull();
        assertThat(changed.emailVerifiedAt()).isEqualTo(clock.instant());
        var after = member("one");
        assertThat(after.at("/login/email").asText()).isEqualTo("new-one@example.test");
        assertThat(after.at("/login/emailStatus").isNull()).isTrue();
        // The contact e-mails of the member record are the club's and stay as they were (R-03-09).
        assertThat(after.at("/contactEmails/0/email").asText()).isEqualTo("old-one@example.test");

        assertThat(audit()).singleElement().satisfies(entry -> {
            assertThat(entry.clubId()).isEqualTo(CLUB);
            assertThat(entry.actorAccountId()).isEqualTo("admin-account");
            assertThat(entry.actorName()).isEqualTo("Example admin");
            assertThat(entry.actorRole()).isEqualTo("ADMIN");
            assertThat(entry.origin()).isEqualTo("BACKOFFICE");
            assertThat(entry.entityType()).isEqualTo("Account");
            assertThat(entry.entityId()).isEqualTo("one-account");
            assertThat(entry.memberId()).isEqualTo("one");
            assertThat(entry.changes()).extracting(item -> item.path() + ":" + item.before() + "->" + item.after())
                    .containsExactlyInAnyOrder("email:old-one@example.test->new-one@example.test", "emailStatus:BOUNCED->null");
        });
        // A link is used once.
        redeem(value).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAGIC_LINK_INVALID"));
        assertThat(audit()).hasSize(1);
    }

    /**
     * AUTH-03 (S03 R-03-06b): D10's login block carries `accessEmailFailedAt` when the account's last access e-mail finally failed
     * (null otherwise); a change of the login address clears it with the suppression mark, as both belong to the old address.
     */
    @Test void AUTH_03_theRecordShowsTheAccessEmailFailureMarkUntilTheAddressChanges() throws Exception {
        assertThat(member("one").at("/login").has("accessEmailFailedAt")).isTrue();
        assertThat(member("one").at("/login/accessEmailFailedAt").isNull()).isTrue();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("one-account")),
                new org.springframework.data.mongodb.core.query.Update().set("accessEmailFailedAt", clock.instant()), "accounts");
        assertThat(java.time.Instant.parse(member("one").at("/login/accessEmailFailedAt").asText())).isEqualTo(clock.instant());
        change("one", "new-one@example.test").andExpect(status().isAccepted());
        assertThat(java.time.Instant.parse(member("one").at("/login/accessEmailFailedAt").asText())).isEqualTo(clock.instant());
        redeem(linkTo("new-one@example.test")).andExpect(status().isOk());
        assertThat(member("one").at("/login/accessEmailFailedAt").isNull()).isTrue();
        assertThat(mongo.getCollection("accounts").find(new org.bson.Document("_id", "one-account")).first()).doesNotContainKey("accessEmailFailedAt");
    }

    @Test void AUTH_02_aNewerRequestSupersedesAnUnusedOneAndATakenAddressChangesNothing() throws Exception {
        change("one", "first-one@example.test").andExpect(status().isAccepted());
        String first = linkTo("first-one@example.test");
        change("one", "second-one@example.test").andExpect(status().isAccepted());
        String second = linkTo("second-one@example.test");
        redeem(first).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAGIC_LINK_INVALID"));
        assertThat(account("one-account").email()).isEqualTo("old-one@example.test");

        // Another account takes the address before the link is used: the link changes nothing and stays unused.
        mongo.insert(new Account("late-account", "second-one@example.test", "Example Late", "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        redeem(second).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAGIC_LINK_INVALID"));
        assertThat(account("one-account").email()).isEqualTo("old-one@example.test");
        assertThat(account("one-account").emailStatus()).isEqualTo(NotificationAccounts.EmailStatus.BOUNCED);
        assertThat(audit()).isEmpty();
    }

    @Test void AUTH_02_anAddressOfAnotherAccountGetsNoLinkAndTheAdminLearnsNothing() throws Exception {
        change("one", "Taken@example.test").andExpect(status().isAccepted()).andExpect(jsonPath("$.sentTo").value("taken@example.test"));
        assertThat(mailbox().messages()).isEmpty();
        assertThat(mongo.count(Query.query(Criteria.where("purpose").is(MagicLinkToken.Purpose.EMAIL_CHANGE)), MagicLinkToken.class)).isZero();
    }

    @Test void AUTH_02_refusalsLeaveTheAccountAsItWas() throws Exception {
        change("one", "OLD-ONE@example.test").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("email")).andExpect(jsonPath("$.details.fieldErrors[0].code").value("UNCHANGED"));
        change("one", "not-an-address").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        change("pending", "new-pending@example.test").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MEMBER_NOT_ACTIVE"));
        // An account that also belongs to another club is not this club's to change (as R-01-14 for its name).
        change("shared", "new-shared@example.test").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("one-account")),
                new org.springframework.data.mongodb.core.query.Update().set("platformRoles", List.of("AGILITYHUB_ADMIN")), Account.class);
        change("one", "new-one@example.test").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        change("one", "new-one@example.test", "INSTRUCTOR").andExpect(status().isForbidden());
        change("missing", "new-missing@example.test").andExpect(status().isNotFound());
        assertThat(mailbox().messages()).isEmpty();
        assertThat(account("one-account").email()).isEqualTo("old-one@example.test");
        assertThat(account("shared-account").email()).isEqualTo("shared@example.test");
    }

    @Test void AUTH_02_theRouteIsTheClubsOwnAndClosedToAnotherClubsAdmin() throws Exception {
        mvc.perform(post("/api/v1/members/one/login-email").header("Host", OTHER + ".example.test").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", "new-one@example.test")))
                        .with(jwt().jwt(j -> j.subject("admin-account").claim("clubId", OTHER)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
        assertThat(mailbox().messages()).isEmpty();
    }
}
