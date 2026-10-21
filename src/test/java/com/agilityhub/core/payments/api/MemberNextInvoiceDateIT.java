package com.agilityhub.core.payments.api;

import com.agilityhub.core.platform.application.Module;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E11-T15 step 3 (the audit of 21-10, F3; ruling E146; S12 R-12-01, R-12-05, R-12-06, S03 §3): the admin's audited route that sets
 * a member's `nextInvoiceDate` — the correction a migrated cash member (paid until December in Playoff) or a member left without a
 * date needs. ADMIN only, never under impersonation, module `BILLING`; the member `ACTIVE`; the date within twelve months either
 * side of the club's current month; `MEMBER_UPDATED` with the reason and `MemberUpdated{diff}`, as a census edit is audited.
 */
class MemberNextInvoiceDateIT extends BillingItSupport {
    static final String REASON = "Pagat fins a desembre a Playoff";
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;

    MockHttpServletRequestBuilder nextInvoiceDate(String member, Object body) throws Exception {
        return keyed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/members/" + member + "/next-invoice-date"), body);
    }
    static Map<String, Object> body(String date, String reason) {
        var body = new LinkedHashMap<String, Object>(); body.put("nextInvoiceDate", date); body.put("reason", reason); return body;
    }

    @Test void T_12_07_E146_theAdminSetsAMembersNextInvoiceDateWithItsReasonAndTheSimulationFollows() throws Exception {
        // Before: Joan Vila (cash, 01-09) is in September's simulation.
        assertThat(simulate("2026-09").path("invoicesPreview").findValuesAsText("memberId")).contains("vila");
        var result = ok(admin(nextInvoiceDate("vila", body("2027-01-01", REASON))), 200);
        assertThat(result.path("nextInvoiceDate").asText()).isEqualTo("2027-01-01");
        assertThat(member("vila", "nextInvoiceDate")).isEqualTo("2027-01-01");
        var audit = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("MEMBER_UPDATED")), Document.class, "audit_entries");
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.getString("entityType")).isEqualTo("Member");
            assertThat(entry.getString("entityId")).isEqualTo("vila");
            assertThat(entry.getString("memberId")).isEqualTo("vila");
            assertThat(entry.getString("reason")).isEqualTo(REASON);
            assertThat(entry.getList("changes", Document.class)).singleElement().satisfies(change -> {
                assertThat(change.getString("path")).isEqualTo("nextInvoiceDate");
                assertThat(String.valueOf(change.get("before"))).isEqualTo("2026-09-01");
                assertThat(String.valueOf(change.get("after"))).isEqualTo("2027-01-01");
            });
        });
        assertThat(events("MemberUpdated")).singleElement().satisfies(event -> {
            var diff = event.get("payload", Document.class).get("diff", Document.class).get("nextInvoiceDate", Document.class);
            assertThat(String.valueOf(diff.get("before"))).isEqualTo("2026-09-01");
            assertThat(String.valueOf(diff.get("after"))).isEqualTo("2027-01-01");
        });
        // September's simulation no longer bills her; the same date again changes nothing and is not audited again.
        assertThat(simulate("2026-09").path("invoicesPreview").findValuesAsText("memberId")).doesNotContain("vila");
        assertThat(ok(admin(nextInvoiceDate("vila", body("2027-01-01", REASON))), 200).path("nextInvoiceDate").asText()).isEqualTo("2027-01-01");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("MEMBER_UPDATED")), "audit_entries")).isEqualTo(1);
        // A member without a date (a pack member moved to a monthly plan before E11-T15) gets one.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("puig")), new Update().unset("nextInvoiceDate"), "members");
        assertThat(simulate("2026-09").path("incidents")).anySatisfy(incident -> {
            assertThat(incident.path("memberId").asText()).isEqualTo("puig");
            assertThat(incident.path("code").asText()).isEqualTo("NO_NEXT_INVOICE_DATE");
        });
        ok(admin(nextInvoiceDate("puig", body("2026-09-01", "Data perduda en canviar de pla"))), 200);
        assertThat(simulate("2026-09").path("invoicesPreview").findValuesAsText("memberId")).contains("puig");
    }

    @Test void T_12_21_E146_theRouteIsTheAdminsOnlyWithinTwelveMonthsAndForAnActiveMember() throws Exception {
        // Twelve months either side of the club's current month (August 2026): 01-08-2025 … 31-08-2027.
        for (String date : List.of("2025-07-31", "2027-09-01")) {
            var refused = call(admin(nextInvoiceDate("vila", body(date, REASON))));
            assertThat(refused.getStatus()).as(date).isEqualTo(400);
            assertThat(json(refused).path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(refused).at("/details/fieldErrors/0/field").asText()).isEqualTo("nextInvoiceDate");
            assertThat(json(refused).at("/details/fieldErrors/0/code").asText()).isEqualTo("INVALID_VALUE");
        }
        ok(admin(nextInvoiceDate("vila", body("2025-08-01", REASON))), 200);
        ok(admin(nextInvoiceDate("vila", body("2027-08-31", REASON))), 200);
        // The reason is required (≤ 200 characters) and so is the date.
        for (var invalid : List.of(body("2026-10-01", " "), body("2026-10-01", "x".repeat(201)), body(null, REASON), body("01/10/2026", REASON))) {
            assertThat(call(admin(nextInvoiceDate("vila", invalid))).getStatus()).as(invalid.toString()).isEqualTo(400);
        }
        // A LEFT or PENDING member → 409 INVALID_STATE; a pending readmission's frozen record names its reason (INC-39).
        error(admin(nextInvoiceDate("pere", body("2026-10-01", REASON))), 409, "INVALID_STATE");
        error(admin(nextInvoiceDate("nuria", body("2026-10-01", REASON))), 409, "INVALID_STATE");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("pere")), new Update().set("status", "PENDING")
                .set("readmissionRequest", new Document("status", "PENDING")), "members");
        var frozen = call(admin(nextInvoiceDate("pere", body("2026-10-01", REASON))));
        assertThat(frozen.getStatus()).isEqualTo(409);
        assertThat(json(frozen).at("/details/reason").asText()).isEqualTo("READMISSION_PENDING");
        // MEMBER and INSTRUCTOR → 403; an impersonation token → 403; another club's member → 404; BILLING off → 404 MODULE_DISABLED.
        for (String role : List.of("MEMBER", "INSTRUCTOR")) { error(as(nextInvoiceDate("vila", body("2026-10-01", REASON)), CLUB, role, "vila"), 403, "FORBIDDEN"); }
        // A real impersonation token of an admin acting as Joan Vila.
        admin("bill-admin", CLUB);
        mongo.save(new com.agilityhub.core.identity.persistence.Account("bill-account-vila", "bill-vila@example.test", "Joan Vila", "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, NOW));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership("bill-account-vila", "bill-account-vila", CLUB, "vila",
                Set.of(com.agilityhub.core.identity.domain.Role.MEMBER), com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE,
                com.agilityhub.core.identity.domain.Role.MEMBER));
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) { issued = impersonations.create("bill-admin", "vila", "Fictional support check"); }
        var impersonated = call(nextInvoiceDate("vila", body("2026-10-01", REASON)).header("Host", HOST).with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER")));
        assertThat(impersonated.getStatus()).as(impersonated.getContentAsString()).isEqualTo(403);
        assertThat(json(impersonated).path("code").asText()).isIn("IMPERSONATION_DENIED", "FORBIDDEN");
        error(as(nextInvoiceDate("vila", body("2026-10-01", REASON)), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("vila")), new Update().set("erasedAt", new Date()), "members");
        error(admin(nextInvoiceDate("vila", body("2026-10-01", REASON))), 409, "MEMBER_ERASED");
        modules(CLUB, Arrays.stream(Module.values()).filter(module -> module != Module.BILLING).toList());
        error(admin(nextInvoiceDate("vila", body("2026-10-01", REASON))), 404, "MODULE_DISABLED");
        assertThat(member("vila", "nextInvoiceDate")).isEqualTo("2027-08-31");
    }
}
