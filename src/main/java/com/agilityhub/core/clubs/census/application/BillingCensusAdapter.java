package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusRules;
import com.agilityhub.core.clubs.census.persistence.Dog;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.map;
import static com.agilityhub.core.clubs.census.application.CensusValues.string;

/**
 * {@link BillingCensusAccess} over the census documents (E8-T02): S12 reads the members it bills and moves their
 * `nextInvoiceDate`; the payment method is read in both stored shapes (`sepa {…}` or the keys at the top level, model §3)
 * and leaves this class masked — the IBAN itself never does.
 */
@Service
public class BillingCensusAdapter implements BillingCensusAccess {
    @org.springframework.beans.factory.annotation.Autowired private CensusEvents events;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<SignupService> signups;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<BillingCensusAdapter> audited;
    private final CensusAccess census; private final Clock clock;
    public BillingCensusAdapter(CensusAccess census, Clock clock) { this.census = census; this.clock = clock; }

    @Override public Optional<Card> card(String memberId) {
        return census.members.findById(memberId).filter(member -> "CARD".equals(map(member.paymentMethod).get("type"))).map(member -> {
            var card = map(member.paymentMethod.get("card"));
            return new Card(string(card.get("stripeCustomerId")), string(card.get("stripePaymentMethodId")), string(card.get("last4")),
                    string(card.get("brand")), Boolean.TRUE.equals(card.get("invalid")));
        });
    }
    @com.agilityhub.core.platform.application.audit.Audited(action = com.agilityhub.core.platform.application.audit.AuditAction.MEMBER_PAYMENT_METHOD_CHANGED,
            entityType = "'Member'", entity = "#memberId", member = "#memberId")
    @Override public void saveCard(String memberId, Card card) {
        var member = census.mutableMember(memberId);
        var stored = new LinkedHashMap<String, Object>();
        stored.put("stripeCustomerId", card.customerId()); stored.put("stripePaymentMethodId", card.paymentMethodId());
        stored.put("last4", card.last4()); stored.put("brand", card.brand()); stored.put("invalid", card.invalid());
        member.paymentMethod = Map.of("type", "CARD", "card", stored); member.updatedAt = clock.instant(); census.members.save(member);
        signups.getObject().refreshDashboard();
        if (!card.invalid()) { events.emit("MemberPaymentMethodChanged", "Member", memberId, Map.of("memberId", memberId, "type", "CARD", "masked", "···· " + card.last4())); }
    }
    @Override public List<String> invalidateCards(String customerId, String paymentMethodId) {
        var criteria = Criteria.where("paymentMethod.type").is("CARD");
        if (customerId != null) { criteria.and("paymentMethod.card.stripeCustomerId").is(customerId); }
        if (paymentMethodId != null) { criteria.and("paymentMethod.card.stripePaymentMethodId").is(paymentMethodId); }
        if (customerId == null && paymentMethodId == null) { return List.of(); }
        var changed = new ArrayList<String>();
        for (var member : census.members.matching(criteria)) {
            var old = card(member.id).orElseThrow();
            // Use the proxy so invalidation records the same audited payment-method change as card setup.
            if (!old.invalid()) { audited.getObject().saveCard(member.id, new Card(old.customerId(), old.paymentMethodId(), old.last4(), old.brand(), true)); changed.add(member.id); }
        }
        return changed;
    }
    @Override public List<BillingMember> activeMembers() {
        return census.members.matching(Criteria.where("status").is("ACTIVE")).stream().map(BillingCensusAdapter::view).toList();
    }
    @Override public Optional<BillingMember> member(String memberId) {
        return memberId == null ? Optional.empty() : census.members.findById(memberId).map(BillingCensusAdapter::view);
    }
    @Override public List<FamilyGroup> activeFamilyGroups() {
        return census.groups.matching(Criteria.where("status").is("ACTIVE")).stream().map(BillingCensusAdapter::group).toList();
    }
    @Override public Optional<FamilyGroup> familyGroupOf(String memberId) {
        var member = census.members.findById(memberId).orElse(null);
        if (member == null || member.familyGroupId == null) { return Optional.empty(); }
        return census.groups.findById(member.familyGroupId).filter(group -> "ACTIVE".equals(group.status) && group.memberIds != null
                && group.memberIds.contains(memberId)).map(BillingCensusAdapter::group);
    }
    @Override public boolean moveNextInvoiceDate(String memberId, LocalDate expected, LocalDate next) {
        // `Member.nextInvoiceDate` goes through `CensusDateConverter` (an ISO string in Mongo): the values stay `LocalDate`s here.
        var criteria = Criteria.where("_id").is(memberId).and("nextInvoiceDate").is(expected);
        var update = new Update().set("updatedAt", clock.instant());
        if (next == null) { update.unset("nextInvoiceDate"); } else { update.set("nextInvoiceDate", next); }
        return census.members.updateFirst(criteria, update);
    }
    @Override public Optional<Instant> lastChange(Collection<String> memberIds) {
        var ids = new ArrayList<>(memberIds == null ? List.of() : memberIds);
        var members = census.members.matching(new Criteria().orOperator(Criteria.where("status").is("ACTIVE"), Criteria.where("_id").in(ids)));
        return Stream.concat(members.stream().map(member -> member.updatedAt), census.groups.findAll().stream().map(group -> group.updatedAt))
                .filter(Objects::nonNull).max(Instant::compareTo);
    }
    /** E8-T03: the stored `iban` or `ibanEncrypted` (either shape of the method), with the mandate; never logged (see the port). */
    @Override public Map<String, SepaAccount> sepaAccounts(Collection<String> memberIds) {
        var accounts = new LinkedHashMap<String, SepaAccount>();
        if (memberIds == null || memberIds.isEmpty()) { return accounts; }
        for (Member member : census.members.matching(Criteria.where("_id").in(List.copyOf(memberIds)))) {
            var stored = member.paymentMethod;
            if (stored == null || !"SEPA_DD".equals(string(stored.get("type")))) { continue; }
            var sepa = map(stored.getOrDefault("sepa", stored));
            var account = new LinkedHashMap<String, Object>();
            for (String key : List.of("iban", "ibanEncrypted")) { if (present(sepa.get(key))) { account.put(key, sepa.get(key)); } }
            accounts.put(member.id, new SepaAccount(member.id, account, string(sepa.get("holderName")), string(sepa.get("mandateRef")),
                    signedAt(sepa.get("mandateSignedAt"))));
        }
        return accounts;
    }
    @Override public Map<String, String> dogNames(Collection<String> dogIds) {
        var names = new LinkedHashMap<String, String>();
        if (dogIds == null || dogIds.isEmpty()) { return names; }
        for (Dog dog : census.dogs.matching(Criteria.where("_id").in(List.copyOf(dogIds)))) { names.put(dog.id, dog.name); }
        return names;
    }

    static BillingMember view(Member member) {
        String taxId = string(map(member.idDocument).get("number"));
        return new BillingMember(member.id, member.memberNumber, member.firstName, member.lastName1, member.lastName2, member.status, member.planId,
                member.nextInvoiceDate, member.leaveDate, member.familyGroupId, payment(member.paymentMethod), taxId,
                string(map(member.signup).get("locale")), member.updatedAt);
    }
    static PaymentMethod payment(Map<String, Object> stored) {
        if (stored == null || stored.get("type") == null) { return null; }
        var sepa = map(stored.getOrDefault("sepa", stored)); var card = map(stored.getOrDefault("card", stored));
        var manual = map(stored.getOrDefault("manual", stored));
        String type = string(stored.get("type"));
        boolean account = present(sepa.get("iban")) || present(sepa.get("ibanEncrypted"));
        String masked = "CARD".equals(type) ? (card.get("last4") == null ? null : "···· " + card.get("last4"))
                : CensusRules.maskedIban(sepa.get("ibanLast4") == null ? string(sepa.get("iban")) : string(sepa.get("ibanLast4")));
        return new PaymentMethod(type, account, "MANUAL".equals(type) ? null : masked, string(sepa.get("holderName")), string(sepa.get("holderTaxId")),
                string(sepa.get("mandateRef")), string(card.get("last4")), Boolean.TRUE.equals(card.get("invalid")), string(manual.get("channel")),
                signedAt(sepa.get("mandateSignedAt")));
    }
    /** `mandateSignedAt` as stored (a date, or an ISO instant); anything else reads as absent, which the SEPA writer refuses. */
    static Instant signedAt(Object raw) {
        try { return CensusValues.instant(raw); } catch (java.time.format.DateTimeParseException unreadable) { return null; }
    }
    private static boolean present(Object value) { return value != null && !value.toString().isBlank(); }
    private static FamilyGroup group(com.agilityhub.core.clubs.census.persistence.FamilyGroup group) {
        return new FamilyGroup(group.id, group.holderMemberId, group.memberIds == null ? List.of() : List.copyOf(group.memberIds));
    }
}
