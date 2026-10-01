package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.DogOwnerAccess;
import com.agilityhub.core.shared.application.MemberIdentityAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListDefinition.Field;
import com.agilityhub.core.shared.application.lists.ListDefinition.Type;
import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * Read-only tenant, ownership and module guards of the reserved S12 operations (E8-T01); no side effects. E8-T02…T06 serve
 * the operations behind them. What a caller may not see — another club's invoice, run, simulation, remittance, payment,
 * pack, member or dog, another member's invoice — is 404, never 403 (T-12-21, CATALEG_ERRORS rule 3).
 */
@Service
public class BillingContractAccess {
    /** `GET /invoices` (S12 §6, D6): the universal list allowlist of CONVENCIONS_API §4; `q` searches the number and the member. */
    public static final ListDefinition INVOICES = new ListDefinition("invoices",
            Map.of("period", new Field("period", Type.TEXT), "status", new Field("status", Type.TEXT), "memberId", new Field("memberId", Type.TEXT),
                    "paymentMethodType", new Field("paymentMethod.type", Type.TEXT), "runId", new Field("runId", Type.TEXT),
                    "remittanceId", new Field("remittanceId", Type.TEXT), "issueDate", new Field("issueDate", Type.DATE),
                    "total", new Field("total.amountMinor", Type.NUMBER), "kind", new Field("kind", Type.TEXT)),
            Map.of("number", "number", "issueDate", "issueDate", "total", "total.amountMinor", "memberLastName", "memberLastName"),
            List.of("displayNumber", "memberSnapshot.fullName"),
            List.of("displayNumber", "member", "concept", "total", "paymentMethodType", "status", "issueDate", "period", "kind", "paidAt", "remittanceId"),
            List.of("displayNumber", "member", "concept", "total", "paymentMethodType", "status"), List.of("number,desc"),
            Set.of("id", "displayNumber", "number", "issueDate", "period", "member", "concept", "total", "paymentMethodType", "status", "kind",
                    "runId", "remittanceId", "refundedTotal", "paidAt", "failedAt", "rolledBack"));
    /** `GET /remittances` (S12 §6): filters `period, status`; no free-text search (CONVENCIONS_API §4, E75). */
    public static final ListDefinition REMITTANCES = new ListDefinition("remittances",
            Map.of("period", new Field("period", Type.TEXT), "status", new Field("status", Type.TEXT)),
            Map.of("period", "period", "creationAt", "creationAt"), List.of(),
            List.of("period", "creationAt", "requestedCollectionDate", "count", "total", "status", "submittedAt"),
            List.of("period", "creationAt", "count", "total", "status"), List.of("creationAt,desc"),
            Set.of("id", "period", "messageId", "creationAt", "requestedCollectionDate", "count", "total", "status", "fileAvailable", "submittedAt"));
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final InvoiceRepository invoices; private final BillingRunRepository runs; private final BillingSimulationRepository simulations;
    private final RemittanceRepository remittances; private final UpfrontPaymentRepository upfront; private final PackBalanceRepository packs;
    private final com.agilityhub.core.payments.persistence.SignupCheckoutRepository checkouts;
    private final MemberIdentityAccess members; private final DogOwnerAccess dogs; private final ClubConfigService configs;
    private final CensusClubSettings clubSettings; private final com.agilityhub.core.shared.application.SignupCapabilities capabilities;
    private final com.agilityhub.core.shared.application.TeamMemberAccess team;
    private final com.agilityhub.core.shared.application.BillingCensusAccess census;
    private final com.agilityhub.core.shared.application.BookingOwnerAccess bookings;
    public BillingContractAccess(InvoiceRepository invoices, BillingRunRepository runs, BillingSimulationRepository simulations, RemittanceRepository remittances,
            UpfrontPaymentRepository upfront, PackBalanceRepository packs, com.agilityhub.core.payments.persistence.SignupCheckoutRepository checkouts,
            MemberIdentityAccess members, DogOwnerAccess dogs, ClubConfigService configs, CensusClubSettings clubSettings,
            com.agilityhub.core.shared.application.SignupCapabilities capabilities, com.agilityhub.core.shared.application.TeamMemberAccess team,
            com.agilityhub.core.shared.application.BillingCensusAccess census, com.agilityhub.core.shared.application.BookingOwnerAccess bookings) {
        this.invoices = invoices; this.runs = runs; this.simulations = simulations; this.remittances = remittances; this.upfront = upfront;
        this.packs = packs; this.checkouts = checkouts; this.members = members; this.dogs = dogs; this.configs = configs;
        this.clubSettings = clubSettings; this.capabilities = capabilities; this.team = team; this.census = census; this.bookings = bookings;
    }

    public void tenant() { TenantContext.require(); }
    /** A `YYYY-MM` month (CONVENCIONS_API §5); anything else is `400 VALIDATION_ERROR {field}`. */
    public void month(String field, String value) {
        if (value == null || !MONTH.matcher(value).matches()) { throw invalid(field); }
    }
    /** An optional parameter with a closed set of values: absent is fine, another value is `400 VALIDATION_ERROR {field}`. */
    public void oneOf(String field, String value, String... allowed) {
        if (value != null && !List.of(allowed).contains(value)) { throw invalid(field); }
    }
    /** `400 VALIDATION_ERROR {field}` (CONVENCIONS_API §6). */
    public static ApiException invalid(String field) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", field, "fieldErrors", List.of(Map.of("field", field, "code", "INVALID_VALUE"))));
    }
    public void invoice(String id) { invoices.findById(id).orElseThrow(BillingContractAccess::notFound); }
    public void invoices(Collection<String> ids) { ids.forEach(this::invoice); }
    /**
     * R-12-27: the caller's own invoice (the impersonated member's under impersonation) or, with `FAMILY_GROUP`, one of the holder
     * of the family group the caller belongs to (E8-T02); another member's is 404 (T-13-24).
     */
    public void ownInvoice(String id) {
        var invoice = invoices.findById(id).orElseThrow(BillingContractAccess::notFound);
        String caller = callerMember();
        if (invoice.memberId().equals(caller)) { return; }
        boolean holder = configs.get(TenantContext.require()).modules().contains(Module.FAMILY_GROUP)
                && census.familyGroupOf(caller).map(group -> invoice.memberId().equals(group.holderMemberId())).orElse(false);
        if (!holder) { throw notFound(); }
    }
    /** The member a `/me/*` call reads (see {@link #callerMember()}), for the services. */
    public String me() { return callerMember(); }
    public void run(String id) { runs.findById(id).orElseThrow(BillingContractAccess::notFound); }
    public void simulation(String id) { simulations.findById(id).orElseThrow(BillingContractAccess::notFound); }
    public void remittance(String id) { remittances.findById(id).orElseThrow(BillingContractAccess::notFound); }
    public void upfrontPayment(String id) { upfront.findById(id).orElseThrow(BillingContractAccess::notFound); }
    public void pack(String id) { packs.findById(id).orElseThrow(BillingContractAccess::notFound); }
    /** A member of the open club (another club's or an unknown one → 404). */
    public void member(String id) { if (members.displayName(id).isEmpty()) { throw notFound(); } }
    /** A member a write is about to change or bill: as {@link #member}, and an erased one → `409 MEMBER_ERASED` (S14 §5). */
    public void mutableMember(String id) { team.teamMember(id); }
    /** A dog of the open club (another club's or an unknown one → 404). */
    public void dog(String id) { if (dogs.ownerOf(id).isEmpty()) { throw notFound(); } }
    /**
     * A dog of {@code memberId} in the open club (E8-T01 round 2: a pack or an upfront payment is the owner's): another member's,
     * another club's or an unknown dog → 404.
     */
    public void memberDog(String memberId, String dogId) {
        if (!dogs.ownerOf(dogId).map(memberId::equals).orElse(false)) { throw notFound(); }
    }
    /**
     * `POST /checkout-sessions` with S12's `bookingId` or `upfrontPaymentIds` (E8-T01 round 2, AGENTS rule 4): after the role,
     * member and club checks of the signup checkout ({@link CheckoutService#authorize}), the booking and every upfront payment
     * named must be {@code memberId}'s in the open club. Another member's (also of the caller's family group), another club's or
     * an unknown one → 404, before the stub, and nothing is written.
     */
    public void checkoutReferences(String memberId, String bookingId, Collection<String> upfrontPaymentIds) {
        if (bookingId != null && !bookings.ownerOf(bookingId).map(memberId::equals).orElse(false)) { throw notFound(); }
        if (upfrontPaymentIds == null) { return; }
        for (String id : upfrontPaymentIds) {
            if (id == null || !upfront.findById(id).map(payment -> memberId.equals(payment.memberId())).orElse(false)) { throw notFound(); }
        }
    }
    /**
     * `GET /checkout-sessions/{id}` (S12 §6 «same as the creator»): the club's ADMIN reads any session of the club, a MEMBER
     * their own, and the anonymous signup screen the one of the member its `X-Signup-Token` capability names (S04 R-04-26).
     */
    public void checkoutSession(String id, String signupToken) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = !(authentication instanceof JwtAuthenticationToken);
        if (anonymous && (signupToken == null || signupToken.isBlank())) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        var session = checkouts.findById(id).orElseThrow(BillingContractAccess::notFound);
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            capabilities.require(session.memberId(), signupToken);
            return;
        }
        boolean admin = CurrentUser.current() != null && CurrentUser.current().impersonation() == null
                && jwt.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        if (!admin && !session.memberId().equals(callerMember())) { throw notFound(); }
    }
    /** The universal list query of `GET /invoices`: an undeclared filter, sort, size, `q` or `fields` key is `400 INVALID_FILTER`. */
    public ListQuery invoiceList(MultiValueMap<String, String> params) { return ListQuery.parse(INVOICES, params); }
    public ListQuery remittanceList(MultiValueMap<String, String> params) { return ListQuery.parse(REMITTANCES, params); }
    /**
     * `POST /webhooks/stripe/{clubId}` runs outside the tenant filter (no JWT, no host). Once {@link StripeWebhookSignatures}
     * authenticated the body (E8-T01 round 2: before anything else), the club needs `BILLING` (404 MODULE_DISABLED) and an
     * enabled `STRIPE` provider (`404`, «club sense Stripe», S12 §6).
     */
    public void stripeClub(String clubId) {
        com.agilityhub.core.platform.application.ClubConfig config;
        try { config = configs.get(clubId); }
        catch (ApiException unknown) { throw notFound(); }
        if (!config.modules().contains(Module.BILLING)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        try (var scope = TenantContext.open(clubId)) { if (!clubSettings.providerEnabled("STRIPE")) { throw notFound(); } }
    }
    /** The member whose data a `/me/*` call reads: the impersonated member under impersonation (R-12-27), else the token's. */
    String callerMember() {
        var user = CurrentUser.current();
        if (user != null && user.impersonation() != null) { return user.impersonation().memberId(); }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String memberId = authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("memberId") : null;
        if (memberId == null || memberId.isBlank()) { throw notFound(); }
        return memberId;
    }
    private static ApiException notFound() { return new ApiException(ErrorCode.NOT_FOUND); }
}
