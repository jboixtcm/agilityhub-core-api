package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.application.ports.PackBalanceOpeningPort;
import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.PackBalance;
import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** S12 R-12-23/24: durable balances and append-only movements in the caller's Mongo transaction. */
@Service
public class PackBalanceService implements PackBalanceOpeningPort {
    private final PackBalanceRepository packs;
    private final BillingCatalogAccess catalog;
    private final BillingCensusAccess census;
    private final ClubConfigService configs;
    private final BillingEvents events;
    private final Clock clock;
    private final ClubClock clubClock;

    public PackBalanceService(PackBalanceRepository packs, BillingCatalogAccess catalog, BillingCensusAccess census,
            ClubConfigService configs, BillingEvents events, Clock clock, ClubClock clubClock) {
        this.packs = packs; this.catalog = catalog; this.census = census; this.configs = configs;
        this.events = events; this.clock = clock; this.clubClock = clubClock;
    }
    public boolean enabled() { return configs.get(TenantContext.require()).modules().contains(Module.PACKS); }
    public List<PackBalance> list(String memberId, String dogId) { return enabled() ? packs.of(memberId, dogId) : List.of(); }
    public PackBalance get(String id) { return packs.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }

    @Override @Transactional
    public void open(String memberId, String dogId, String upfrontPaymentId, LocalDate paidOn) {
        if (!enabled() || packs.forPayment(upfrontPaymentId).isPresent()) { return; }
        var member = census.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        open(memberId, dogId, census.packPlan(memberId, dogId), upfrontPaymentId, paidOn, null, null, null);
    }

    @Transactional @RequiresModule(Module.PACKS)
    public PackBalance open(String memberId, String dogId, String planId, String upfrontPaymentId, LocalDate openedOn,
            Integer sessionsTotal, LocalDate expiresOn, String reason) {
        var terms = catalog.pack(planId);
        if (upfrontPaymentId != null) {
            var existing = packs.forPayment(upfrontPaymentId);
            if (existing.isPresent()) { return existing.get(); }
        }
        if (dogId == null) { throw BillingContractAccess.invalid("dogId"); }
        int total = sessionsTotal == null ? terms.sessions() : sessionsTotal;
        LocalDate expiry = expiresOn == null ? openedOn.plusMonths(terms.validityMonths()).minusDays(1) : expiresOn;
        if (total <= 0 || expiry.isBefore(openedOn)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var now = clock.instant();
        var pack = packs.insert(new PackBalance(UUID.randomUUID().toString(), TenantContext.require(), memberId, dogId, planId,
                upfrontPaymentId, total, 0, total, openedOn.toString(), expiry.toString(), PackBalanceState.ACTIVE,
                List.of(movement(PackMovementType.OPEN, total, null, reason)), null, null, null, Map.of(), null, now, BillingEvents.actor()));
        publish(BillingEvent.Kind.PackOpened, pack, null, Map.of());
        return pack;
    }

    /** S08 R-08-17: only a PACK plan selects or consumes a balance; historical refunds remain independent of the plan. */
    private boolean packPlan(String memberId) {
        return census.member(memberId).flatMap(m -> catalog.plan(m.planId())).map(p -> "PACK".equals(p.type())).orElse(false);
    }
    public Optional<Balance> balance(String memberId, String dogId, LocalDate classDate) {
        if (!enabled() || !packPlan(memberId)) { return Optional.empty(); }
        var all = packs.of(memberId, dogId);
        var usable = firstToExpire(all, classDate);
        if (usable.isPresent()) { return usable.map(PackBalanceService::summary); }
        if (!all.isEmpty()) {
            var p = all.stream().max(EXPIRY).orElseThrow();
            return Optional.of(new Balance(p.id(), p.sessionsTotal(), p.consumed(), 0, LocalDate.parse(p.expiresOn())));
        }
        return Optional.of(new Balance(null, 0, 0, 0, null));
    }
    public record Balance(String id, int total, int consumed, int remaining, LocalDate expiresOn) { }
    private static Balance summary(PackBalance p) {
        return new Balance(p.id(), p.sessionsTotal(), p.consumed(), p.remaining(), LocalDate.parse(p.expiresOn()));
    }
    private static final Comparator<PackBalance> EXPIRY = Comparator.comparing((PackBalance p) -> LocalDate.parse(p.expiresOn())).thenComparing(PackBalance::id);
    /** R-12-24: among the packs usable on the class date, the one that expires first. */
    private static Optional<PackBalance> firstToExpire(List<PackBalance> all, LocalDate date) {
        return all.stream().filter(p -> usable(p, date)).min(EXPIRY);
    }
    private static boolean usable(PackBalance p, LocalDate date) {
        return p.state() == PackBalanceState.ACTIVE && p.remaining() > 0 && !LocalDate.parse(p.expiresOn()).isBefore(date);
    }

    @Transactional
    public String consume(String memberId, String dogId, String bookingId, LocalDate classDate) {
        if (!enabled() || !packPlan(memberId)) { return null; }
        var prior = packs.forBooking(bookingId);
        if (prior.isPresent()) {
            var movement = prior.get().movements().stream().filter(m -> m.type() == PackMovementType.CONSUME && bookingId.equals(m.bookingId())).findFirst();
            if (movement.isPresent()) { return movement.get().id(); }
        }
        var pack = firstToExpire(packs.of(memberId, dogId), classDate).orElseThrow(() -> new ApiException(ErrorCode.PACK_EMPTY));
        var movement = movement(PackMovementType.CONSUME, -1, bookingId, null);
        int remaining = pack.remaining() - 1;
        boolean low = pack.lowBalanceNotifiedAt() == null && remaining <= configs.get(TenantContext.require()).get("billing.packLowBalanceSessions", Integer.class);
        var next = write(pack, movement, pack.consumed() + 1, remaining, pack.state(), pack.expiresOn(), pack.expiredAt(), low ? clock.instant() : pack.lowBalanceNotifiedAt());
        publish(BillingEvent.Kind.PackConsumed, next, bookingId, Map.of());
        if (low) { publish(BillingEvent.Kind.PackLowBalance, next, null, Map.of()); }
        return movement.id();
    }

    @Transactional
    public String refund(String bookingId) {
        if (!enabled()) { return null; }
        var found = packs.forBooking(bookingId);
        if (found.isEmpty()) { return null; }
        var pack = found.get();
        if (pack.movements().stream().anyMatch(m -> m.type() == PackMovementType.REFUND && bookingId.equals(m.bookingId()))) { return null; }
        if (pack.movements().stream().noneMatch(m -> m.type() == PackMovementType.CONSUME && bookingId.equals(m.bookingId()))) { return null; }
        var movement = movement(PackMovementType.REFUND, 1, bookingId, null);
        // B11: the balance remains EXPIRED and unusable; a refund is still visible in its history.
        var next = write(pack, movement, pack.consumed() - 1, pack.remaining() + 1, pack.state(), pack.expiresOn(), pack.expiredAt(), pack.lowBalanceNotifiedAt());
        publish(BillingEvent.Kind.PackRefunded, next, bookingId, Map.of());
        return movement.id();
    }

    @Transactional @RequiresModule(Module.PACKS)
    @Audited(action = AuditAction.PACK_ADJUSTED, entityType = "'PackBalance'", entity = "#id", reason = "#reason")
    public PackBalance adjust(String id, int delta, String reason, LocalDate expiresOn) {
        var pack = get(id);
        int remaining = Math.addExact(pack.remaining(), delta);
        if (remaining < 0) { throw new ApiException(ErrorCode.PACK_NEGATIVE); }
        if (pack.state() == PackBalanceState.EXPIRED && expiresOn == null || expiresOn != null && expiresOn.isBefore(clubClock.today(TenantContext.require()))) {
            throw BillingContractAccess.invalid("expiresOn");
        }
        var next = write(pack, movement(PackMovementType.ADJUST, delta, null, reason), pack.consumed(), remaining,
                expiresOn == null ? pack.state() : PackBalanceState.ACTIVE, expiresOn == null ? pack.expiresOn() : expiresOn.toString(),
                expiresOn == null ? pack.expiredAt() : null, pack.lowBalanceNotifiedAt());
        publish(BillingEvent.Kind.PackAdjusted, next, null, Map.of("delta", delta, "reason", reason));
        return next;
    }

    @Transactional
    public void expire(String id) {
        if (!enabled()) { return; }
        var pack = get(id);
        if (pack.state() != PackBalanceState.ACTIVE) { return; }
        var next = write(pack, movement(PackMovementType.EXPIRE, 0, null, null), pack.consumed(), pack.remaining(),
                PackBalanceState.EXPIRED, pack.expiresOn(), clock.instant(), pack.lowBalanceNotifiedAt());
        publish(BillingEvent.Kind.PackExpired, next, null, Map.of());
    }
    private PackBalance.Movement movement(PackMovementType type, int delta, String bookingId, String reason) {
        return new PackBalance.Movement(UUID.randomUUID().toString(), type, delta, bookingId, reason, BillingEvents.actor(), clock.instant());
    }
    private PackBalance write(PackBalance old, PackBalance.Movement movement, int consumed, int remaining, PackBalanceState state,
            String expiresOn, Instant expiredAt, Instant lowAt) {
        var movements = new ArrayList<>(old.movements()); movements.add(movement);
        return packs.save(new PackBalance(old.id(), old.clubId(), old.memberId(), old.dogId(), old.planId(), old.upfrontPaymentId(), old.sessionsTotal(),
                consumed, remaining, old.openedOn(), expiresOn, state, List.copyOf(movements), old.expiryWarnedAt(), expiredAt, lowAt,
                old.sourceIds(), old.version() + 1, old.createdAt(), old.createdByAccountId()), old.version());
    }
    private void publish(BillingEvent.Kind kind, PackBalance pack, String bookingId, Map<String, Object> extra) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("packBalanceId", pack.id()); payload.put("memberId", pack.memberId()); payload.put("dogId", pack.dogId());
        payload.put("expiresOn", pack.expiresOn()); payload.put("remaining", pack.remaining());
        if (bookingId != null) { payload.put("bookingId", bookingId); }
        payload.putAll(extra); events.publish(kind, pack.id(), payload);
    }
}
