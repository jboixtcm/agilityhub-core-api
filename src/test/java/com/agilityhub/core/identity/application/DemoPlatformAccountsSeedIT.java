package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.census.application.DemoSeedCommand;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E10-T03 step 4 (organizer note E118): `seed:demo` gives the Cànic's stack a fictional `AGILITYHUB_ADMIN` account with no club
 * role, which signs in through AgilityHub ID with a token without `clubId` and opens the console (S17 §2 «Entrada»), is refused a
 * club's data (T-17-10), and a second run changes nothing; an existing account keeps its own data and only gains the role.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class DemoPlatformAccountsSeedIT extends AbstractIntegrationTest {
    static final String EMAIL = "platform@example.test";
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedCommand command; @Autowired MongoTemplate mongo;
    @Autowired AccountRepository accounts; @Autowired PasswordHasher passwords; @Autowired DemoPlatformAccountsSeeder seeder; @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    String club;
    @BeforeEach void clear() {
        wipeDatabaseKeepingBootstrap(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        club = definitions.apply(codec.read(Path.of("seeds/club-canic.yaml")), false).id();
    }
    void seed() { command.run(new DefaultApplicationArguments("--club=canic", "--seed=42", "--week-start=2026-09-14")); }
    org.springframework.mock.web.MockHttpServletResponse login(String host, String client) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Host", host).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "password")
                .param("client_id", client).param("username", EMAIL).param("password", "Fictional-seed-password")).andReturn().getResponse();
    }
    static final String ID = "id.agilitydoghub.com";
    /**
     * S17 §2 «Entrada» and S01 R-01-17: the console signs in through AgilityHub ID, whose global token (`id-web`, on the issuer's
     * host) carries the platform roles and no club; a club client's code flow needs a club host (`OidcService`).
     */
    String consoleToken() throws Exception {
        var signedIn = login(ID, "id-web");
        assertThat(signedIn.getStatus()).as(signedIn.getContentAsString()).isEqualTo(200);
        return mapper.readTree(signedIn.getContentAsString()).path("access_token").asText();
    }

    @Test void T_17_10_R_17_12_theDemoSeedHasAPlatformAccountWithoutAClubRoleAndRepeatsWithoutWrites() throws Exception {
        seed();
        var account = accounts.findByEmail(EMAIL).orElseThrow();
        assertThat(account.platformRoles()).containsExactly(Account.PlatformRole.AGILITYHUB_ADMIN);
        assertThat(account.status()).isEqualTo(Account.Status.ACTIVE);
        assertThat(account.name()).isEqualTo("Pat Plataforma Example");
        assertThat(passwords.verify("Fictional-seed-password", account.passwordHash())).isTrue();
        assertThat(account.onboardingPending()).isFalse();
        assertThat(mongo.count(Query.query(Criteria.where("accountId").is(account.id())), "memberships")).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("accountId").is(account.id())), "members")).isZero();
        assertThat(mongo.findById(club + ":planning", Document.class, "demo_seed_runs").get("counts", Document.class)).containsEntry("platformAccounts", 1);

        // The console's entry: the AgilityHub ID token has no club and carries the platform role; the console answers on the product host.
        String token = consoleToken();
        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getStringListClaim("platformRoles")).containsExactly("AGILITYHUB_ADMIN");
        assertThat(claims.getClaim("clubId")).isNull();
        assertThat(claims.getAudience()).containsExactly("id-web");
        var console = mvc.perform(get("/api/v1/platform/clubs").header("Host", "clubsadmin.agilitydoghub.com").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(console.getStatus()).as(console.getContentAsString()).isEqualTo(200);
        assertThat(mapper.readTree(console.getContentAsString()).path("items").findValues("slug").stream().map(Object::toString)).contains("\"canic\"");
        // T-17-10: on the club's own host it has no membership.
        var refused = login("app.agilitycanic.cat", "clubs-admin");
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(mapper.readTree(refused.getContentAsString()).path("code").asText()).isEqualTo("NO_MEMBERSHIP");

        // A second run writes nothing.
        var before = mongo.findAll(Document.class, "accounts");
        seed();
        assertThat(mongo.findAll(Document.class, "accounts")).isEqualTo(before);
    }

    @Test void T_17_10_R_17_12_anExistingAccountKeepsItsDataAndOnlyGainsThePlatformRole() {
        accounts.save(new Account("existing-platform", EMAIL, "Already Here", "es", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        var section = Map.<String, Object>of("platform", Map.of("accounts", List.of(Map.of("email", EMAIL, "name", "Pat Plataforma Example", "locale", "ca",
                "password", "${SEED_PASSWORD}"))));
        var input = new DemoSeedStep.Input(section, 42, LocalDate.parse("2026-09-14"), "nobody", Set.of(), List.of(), LocalDate.parse("2026-09-09"), false);
        assertThat(seeder.apply(input)).containsExactly(Map.entry("platformAccounts", 1));
        var account = accounts.findById("existing-platform").orElseThrow();
        assertThat(account.platformRoles()).containsExactly(Account.PlatformRole.AGILITYHUB_ADMIN);
        assertThat(account.name()).isEqualTo("Already Here"); assertThat(account.locale()).isEqualTo("es"); assertThat(account.passwordHash()).isNull();
        assertThat(mongo.count(Query.query(Criteria.where("email").is(EMAIL)), "accounts")).isEqualTo(1);
        // A seed without the section adds nothing.
        assertThat(seeder.apply(new DemoSeedStep.Input(Map.of(), 42, LocalDate.parse("2026-09-14"), "nobody", Set.of(), List.of(),
                LocalDate.parse("2026-09-09"), false))).isEmpty();
    }
}
