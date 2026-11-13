package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static com.agilityhub.core.clubs.census.application.CensusValues.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WEB-01 (owner decision 13-11-2026, S03 R-03-06): D10's welcome e-mail (`POST /members/{id}/access-resend`) is limited per club,
 * whatever the administrator's address, and no longer spends the magic-link IP bucket that the members' own sign-in requests from
 * the club's network use. Small capacities make the limits visible; fictional people only.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = {"core.security.rate-limits.enabled=true",
        "core.security.rate-limits.access-resend-club.capacity=3", "core.security.rate-limits.magic-link-ip.capacity=2"})
class WelcomeRateLimitIT extends AbstractIntegrationTest {
    static final String CLUB = "welcome-a", OTHER = "welcome-b";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired CensusRepository<Member> members;
    int number;

    void member(String club, String id, Set<Role> roles) {
        mongo.insert(new Account(id + "-account", id + "@example.test", "Example " + id, "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.insert(new Membership(id + "-membership", id + "-account", club, id, roles, Membership.Status.ACTIVE, Role.MEMBER));
        var member = new Member(); member.id = id; member.clubId = club; member.accountId = id + "-account"; member.memberNumber = ++number;
        member.idDocument = object("type", "PASSPORT", "number", "fictional-" + id);
        member.firstName = "Example"; member.lastName1 = id; member.gender = "OTHER"; member.birthDate = LocalDate.of(1990, 1, 1);
        member.contactEmails = List.of(object("email", id + "@example.test", "bounced", false));
        member.phones = List.of(object("prefix", "+34", "number", "600000001", "label", "Primary"));
        member.address = object("street", "1 Example Street", "postalCode", "08349", "city", "Example Town", "country", "ES");
        member.status = "ACTIVE"; member.joinedAt = Instant.parse("2020-01-01T00:00:00Z"); member.bookingBlock = object("active", false);
        try (var tenant = TenantContext.open(club)) { members.insert(member); }
    }

    ResultActions welcome(String club, String member, String ip) throws Exception {
        return mvc.perform(post("/api/v1/members/" + member + "/access-resend").header("Host", club + ".example.test")
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .with(jwt().jwt(j -> j.subject("admin-" + club + "-account").claim("clubId", club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test void WEB_01_theWelcomeIsLimitedPerClubAndLeavesTheMembersSignInBucketAlone() throws Exception {
        TenantContext.clear();
        for (String collection : List.of("accounts", "memberships", "members", "audit_entries", "domain_events", "notifications", "magic_link_tokens")) {
            mongo.remove(new Query(), collection);
        }
        for (String club : List.of(CLUB, OTHER)) {
            if (clubs.findById(club).isEmpty()) { clubs.save(PlatformFixtures.club(club, club + ".example.test")); }
            configs.invalidate(club);
        }
        hosts.invalidate();
        member(CLUB, "admin-" + CLUB, Set.of(Role.MEMBER, Role.ADMIN));
        member(OTHER, "admin-" + OTHER, Set.of(Role.MEMBER, Role.ADMIN));
        for (String id : List.of("one", "two", "three", "four")) { member(CLUB, id, Set.of(Role.MEMBER)); }
        member(OTHER, "five", Set.of(Role.MEMBER));

        // Two welcomes from the club's office address: the magic-link IP bucket (capacity 2 here) is not spent by them.
        welcome(CLUB, "one", "198.51.100.10").andExpect(status().isAccepted()).andExpect(jsonPath("$.sentTo").value("one@example.test"));
        welcome(CLUB, "two", "198.51.100.10").andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/auth/magic-link").header("Host", CLUB + ".example.test").contentType("application/json")
                        .with(request -> { request.setRemoteAddr("198.51.100.10"); return request; })
                        .content(mapper.writeValueAsString(Map.of("email", "one@example.test", "purpose", "LOGIN", "client_id", "clubs-app"))))
                .andExpect(status().isAccepted());

        // The club's bucket (capacity 3 here) is shared by every address its administrators send from.
        welcome(CLUB, "three", "203.0.113.20").andExpect(status().isAccepted());
        welcome(CLUB, "four", "192.0.2.30").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.retryAfter").isNumber());
        // Another club has its own.
        welcome(OTHER, "five", "192.0.2.30").andExpect(status().isAccepted());

        // One audited ACCESS_RESENT per welcome that went out; the refused one wrote nothing.
        assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.ACCESS_RESENT)), AuditEntry.class))
                .extracting(AuditEntry::memberId).containsExactlyInAnyOrder("one", "two", "three", "five");
    }
}
