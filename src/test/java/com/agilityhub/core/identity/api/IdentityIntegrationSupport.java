package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.PasswordHasher;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
abstract class IdentityIntegrationSupport extends AbstractIntegrationTest {
    static final String HOST = "app.example.test";
    static final String PASSWORD = "Example-password-for-tests";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired AccountRepository accounts;
    @Autowired MembershipRepository memberships;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordHasher passwords;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired java.util.concurrent.ThreadPoolExecutor magicLinkExecutor;
    @Autowired com.agilityhub.core.clubs.messaging.application.EmailSender emailSender;
    void awaitMail() throws Exception { magicLinkExecutor.submit(() -> { }).get(20, java.util.concurrent.TimeUnit.SECONDS); }
    @org.junit.jupiter.api.AfterEach void drainMail() throws Exception { awaitMail(); }
    @BeforeEach void prepareIdentity() throws Exception {
        awaitMail();
        if (emailSender instanceof com.agilityhub.core.clubs.messaging.application.FakeEmailSender fake) { fake.clear(); }
        TenantContext.clear();
        for (Class<?> type : new Class<?>[]{Account.class, Membership.class, RefreshToken.class, MagicLinkToken.class, Club.class,
                com.agilityhub.core.platform.persistence.Parameter.class, com.agilityhub.core.platform.persistence.SecurityEvent.class,
                com.agilityhub.core.shared.persistence.DomainEventRecord.class,
                com.agilityhub.core.clubs.messaging.persistence.Notification.class}) {
            mongo.remove(new Query(), type);
        }
        clubs.save(PlatformFixtures.club("club-a", HOST));
        clubs.save(PlatformFixtures.club("club-b", "b.example.test"));
        configs.invalidate("club-a"); configs.invalidate("club-b"); hosts.invalidate();
        accounts.save(new Account("account-a", "admin@example.test", "Example Admin", "en", passwords.hash(PASSWORD),
                Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        membership("club-a", Set.of(Role.ADMIN), Role.ADMIN, "member-a");
    }
    void membership(String club, Set<Role> roles, Role profile, String member) {
        try (var scope = TenantContext.open(club)) {
            memberships.replace(new Membership("membership-" + club, "account-a", club, member, roles, Membership.Status.ACTIVE, profile));
        }
    }
    ResultActions login(String host, String email, String password) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "password").param("username", email).param("password", password));
    }
    JsonNode login() throws Exception {
        return mapper.readTree(login(HOST, "admin@example.test", PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    ResultActions refresh(String value, String host, String client) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token").param("client_id", client).param("refresh_token", value));
    }
}
