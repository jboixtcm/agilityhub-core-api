package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Membership;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AUTH-01 (S01 R-01-05, owner decision of 13 Nov 2026): a staff account (ADMIN or INSTRUCTOR in any club, or AGILITYHUB_ADMIN) sets
 * or changes its password to 12 characters at least; a member keeps `auth.passwordMinLength` (8). `PASSWORD_TOO_SHORT` names the
 * account's minimum. Fictional accounts and passwords only.
 */
class StaffPasswordIT extends IdentityIntegrationSupport {
    /** The breached-password lookup is a third-party service: never called from a test (it answers «not breached»). */
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.agilityhub.core.identity.application.CompromisedPasswords compromised;
    ResultActions change(JsonNode session, String next) throws Exception {
        return mvc.perform(put("/api/v1/me/password").header("Host", HOST).header("Authorization", "Bearer " + session.path("access_token").asText())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("current", PASSWORD, "new", next, "repeat", next))));
    }

    @Test void AUTH_01_aClubAdministratorNeedsTwelveCharacters() throws Exception {
        var session = login();
        change(session, "Fictional-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT")).andExpect(jsonPath("$.details.minLength").value(12));
        change(session, "Fictional-12").andExpect(status().isOk());
        assertThat(passwords.verify("Fictional-12", accounts.findById("account-a").orElseThrow().passwordHash())).isTrue();
    }

    @Test void AUTH_01_aMemberKeepsEightCharacters() throws Exception {
        membership("club-a", Set.of(Role.MEMBER), Role.MEMBER, "member-a");
        var session = login();
        change(session, "Fiction").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT")).andExpect(jsonPath("$.details.minLength").value(8));
        change(session, "Fiction1").andExpect(status().isOk());
    }

    /** The password belongs to the account: an INSTRUCTOR role in another club counts, an erased membership does not. */
    @Test void AUTH_01_aStaffRoleInAnyClubCountsButNotAnErasedOne() throws Exception {
        membership("club-a", Set.of(Role.MEMBER), Role.MEMBER, "member-a");
        membership("club-b", Set.of(Role.INSTRUCTOR), Role.INSTRUCTOR, "member-b");
        var session = login();
        change(session, "Fictional-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.minLength").value(12));

        mongo.updateFirst(Query.query(Criteria.where("clubId").is("club-b").and("accountId").is("account-a")),
                new Update().set("status", Membership.Status.ERASED), Membership.class);
        change(session, "Fictional").andExpect(status().isOk());
    }
}
