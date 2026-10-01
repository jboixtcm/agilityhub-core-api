package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.domain.PendingChargeRules;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.PendingCharge;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * S12 R-12-25 (E8-T02, the domain half of T-12-08): a `CHARGE_ON_ATTENDANCE` single class becomes one `PendingCharge` (unique
 * per booking) when it is marked `PRESENT`/`NO_SHOW` from `PENDING` or cancelled late, at the `SINGLE_CLASS` price of the
 * member's plan current on the class date (none: no charge and a warning, never an invented amount), with its description
 * frozen in the club's language («Classe 06/10 — Duna»); marked back to `PENDING` before billing it is voided, and a later
 * mark reinstates it. Idempotent: a repeated event finds the same charge. Without `BILLING` or `SINGLE_CLASS` nothing
 * happens. The S08 consumers that call it own the booking's `charge.chargeInvoiceLineRef` stamp.
 */
@Service
public class PendingChargeService {
    private static final Logger LOG = LoggerFactory.getLogger(PendingChargeService.class);
    /** A booking of a single class as its consumers see it: the dog's owner, the dog and the class's club-local day. */
    public record ChargedBooking(String bookingId, String memberId, String dogId, String dogName, LocalDate classDate) { }

    private final PendingChargeRepository charges; private final BillingCensusAccess census; private final BillingCatalogAccess catalog;
    private final ClubConfigService configs; private final BillingTexts texts; private final Clock clock;
    public PendingChargeService(PendingChargeRepository charges, BillingCensusAccess census, BillingCatalogAccess catalog, ClubConfigService configs,
            BillingTexts texts, Clock clock) {
        this.charges = charges; this.census = census; this.catalog = catalog; this.configs = configs; this.texts = texts; this.clock = clock;
    }

    /** S10 `AttendanceMarked{state, previousState}`: the id of the booking's live (not voided) charge after the mark, if any. */
    public Optional<String> attendance(ChargedBooking booking, String state, String previousState) {
        if (!enabled()) { return Optional.empty(); }
        var existing = charges.forBooking(booking.bookingId());
        return live(apply(booking, PendingChargeRules.onAttendance(state, previousState, existing(existing)), existing));
    }
    /** S08 `BookingCancelled{late}`: a late cancellation is charged; the id of the booking's live charge, if any. */
    public Optional<String> cancellation(ChargedBooking booking, boolean late) {
        if (!enabled()) { return Optional.empty(); }
        var existing = charges.forBooking(booking.bookingId());
        return live(apply(booking, PendingChargeRules.onCancellation(late, existing(existing)), existing));
    }
    private static Optional<String> live(Optional<PendingCharge> charge) { return charge.filter(pending -> pending.voidedAt() == null).map(PendingCharge::id); }
    /** `GET /members/{id}/pending-charges`: the member's charges, newest first, billed and voided ones included. */
    public List<PendingCharge> forMember(String memberId) { return charges.forMember(memberId); }

    private Optional<PendingCharge> apply(ChargedBooking booking, PendingChargeRules.Action action, Optional<PendingCharge> existing) {
        switch (action) {
            case CHARGE -> { return create(booking); }
            case VOID -> { charges.voided(existing.orElseThrow().id(), clock.instant()); return charges.forBooking(booking.bookingId()); }
            case REINSTATE -> { charges.voided(existing.orElseThrow().id(), null); return charges.forBooking(booking.bookingId()); }
            default -> { return existing; }
        }
    }
    private Optional<PendingCharge> create(ChargedBooking booking) {
        var config = configs.get(TenantContext.require());
        var plan = census.member(booking.memberId()).map(BillingCensusAccess.BillingMember::planId).orElse(null);
        var price = catalog.currentPrice(plan, "SINGLE_CLASS", booking.classDate()).orElse(null);
        if (price == null) {
            // R-12-03's rule for single classes too: an amount is never invented.
            LOG.warn("PendingChargeWithoutPrice clubId={} bookingId={}", TenantContext.require(), booking.bookingId());
            return Optional.empty();
        }
        String description = texts.singleClass(booking.classDate(), booking.dogName(), Locale.forLanguageTag(config.club().defaultLocale()));
        var charge = new PendingCharge(UUID.randomUUID().toString(), TenantContext.require(), booking.memberId(), booking.dogId(), booking.bookingId(),
                price.id(), price.amount(), description, clock.instant(), null, null);
        return Optional.of(charges.insert(charge));
    }
    private boolean enabled() {
        var modules = configs.get(TenantContext.require()).modules();
        return modules.contains(Module.BILLING) && modules.contains(Module.SINGLE_CLASS);
    }
    private static PendingChargeRules.Existing existing(Optional<PendingCharge> charge) {
        if (charge.isEmpty()) { return PendingChargeRules.Existing.NONE; }
        if (charge.get().invoiceId() != null) { return PendingChargeRules.Existing.BILLED; }
        return charge.get().voidedAt() != null ? PendingChargeRules.Existing.VOIDED : PendingChargeRules.Existing.OPEN;
    }
}
