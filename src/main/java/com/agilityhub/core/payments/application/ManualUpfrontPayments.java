package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiresModule(Module.BILLING)
public class ManualUpfrontPayments {
    private final UpfrontPaymentRepository payments; private final PackBalanceService packs; private final BillingCensusAccess census;
    private final BillingCatalogAccess catalog; private final PaymentAudits audit; private final BillingEvents events; private final Clock clock; private final ClubClock local;
    public ManualUpfrontPayments(UpfrontPaymentRepository payments, PackBalanceService packs, BillingCensusAccess census, BillingCatalogAccess catalog,
            PaymentAudits audit, BillingEvents events, Clock clock, ClubClock local) {
        this.payments = payments; this.packs = packs; this.census = census; this.catalog = catalog; this.audit = audit; this.events = events; this.clock = clock; this.local = local;
    }
    public List<Map<String,Object>> list(String memberId, String status) {
        return payments.member(memberId).stream().filter(p -> status == null || p.status().equals(status))
                .sorted(Comparator.comparing(UpfrontPayment::createdAt).reversed()).map(this::view).toList();
    }
    @Transactional
    public Map<String,Object> record(String memberId, String dogId, String concept, Money due, Money paid, String channel, LocalDate paidOn, String reference, String note) {
        var member = census.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!due.currency().equals(paid.currency())) { throw new ApiException(ErrorCode.CURRENCY_MISMATCH); }
        if (due.amountMinor() < 0 || paid.amountMinor() < 0) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        if (paid.amountMinor() > due.amountMinor()) { throw new ApiException(ErrorCode.AMOUNT_EXCEEDS_DUE); }
        if ("PACK".equals(concept)) {
            if (!packs.enabled()) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
            catalog.pack(member.planId()); if (dogId == null) { throw BillingContractAccess.invalid("dogId"); }
        }
        String id = UUID.randomUUID().toString(); String packId = null;
        var at = paidOn.atStartOfDay(local.now(TenantContext.require()).getZone()).toInstant();
        if ("PACK".equals(concept) && paid.equals(due)) { packId = packs.open(memberId, dogId, member.planId(), id, paidOn, null, null, null).id(); }
        var payment = payments.insert(new UpfrontPayment(id, TenantContext.require(), memberId, dogId, concept, concept, due, paid,
                paid.equals(due) ? "PAID" : paid.amountMinor() == 0 ? "DUE" : "PARTIAL", "MANUAL", null, clock.instant(), at, null,
                null, null, null, packId, null, channel, reference, List.of(), note));
        events.publish(com.agilityhub.core.payments.domain.BillingEvent.Kind.UpfrontPaymentRecorded, id,
                Map.of("paymentId", id, "memberId", memberId, "concept", concept, "provider", "MANUAL", "amountPaid", paid));
        audit.recorded(id, memberId, paid); return view(payment);
    }
    private Map<String,Object> view(UpfrontPayment p) {
        var out = new LinkedHashMap<String,Object>();
        out.put("id", p.id()); out.put("memberId", p.memberId()); out.put("dogId", p.dogId()); out.put("concept", p.concept()); out.put("signupConcept", p.signupConcept());
        out.put("amountDue", p.amountDue()); out.put("amountPaid", p.amountPaid()); out.put("status", p.status());
        var provider = new LinkedHashMap<String,Object>(); provider.put("type", p.provider()); provider.put("channel", p.channel()); provider.put("paidAt", p.paidAt()); provider.put("reference", p.reference());
        provider.put("checkoutSessionId", p.checkoutSessionId()); provider.put("paymentIntentId", p.stripe() == null ? null : p.stripe().paymentIntentId()); provider.put("chargeId", p.stripe() == null ? null : p.stripe().chargeId());
        out.put("provider", p.provider() == null ? null : provider); out.put("bookingId", p.bookingId()); out.put("activityRegistrationId", p.activityRegistrationId());
        out.put("packBalanceId", p.packBalanceId()); out.put("refunds", p.refunds() == null ? List.of() : p.refunds()); out.put("note", p.note()); out.put("createdAt", p.createdAt()); out.put("paidAt", p.paidAt()); return out;
    }
}
