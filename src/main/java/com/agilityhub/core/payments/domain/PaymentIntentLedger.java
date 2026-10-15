package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.*;

/**
 * E105: one immutable view of a captured intent, loaded under its transaction fence. The rows are projections of the
 * capture and settled refunds; reservations include local commands, unlinked dashboard refunds and current interventions.
 * Every decision uses this same view. In particular, a row's historical refund placement cannot protect its neighbour's
 * unbilled credit from absorption. Rebuild the view after persisting a decision, before deciding anything else.
 * <p>
 * {@code interventions} lists the rows whose compensation is in intervention, in the order in which the compensations failed,
 * then row order (E111). Their claims are the lowest-priority reservations: {@link #claims()} recomputes them in that order
 * from every other reservation, and an unlinked dashboard refund is placed as if they were absent, on those rows first.
 */
public record PaymentIntentLedger(List<Row> rows, List<Reservation> reservations, List<String> interventions) {
    public record Row(String id, long captured, long refunded, long credited, boolean billed) { }
    public record Reservation(String id, String targetId, long amount, boolean claim) {
        public Reservation(String id, String targetId, long amount) { this(id, targetId, amount, false); }
        public static Reservation intervention(String rowId, long owed) { return new Reservation(rowId, rowId, owed, true); }
    }

    public PaymentIntentLedger {
        rows = List.copyOf(rows);
        reservations = List.copyOf(reservations);
        interventions = List.copyOf(interventions);
    }

    public PaymentIntentLedger(List<Row> rows, List<Reservation> reservations) { this(rows, reservations, List.of()); }

    /**
     * E111: each intervention's due, ignoring the stored claims. In the split order, a row owes what it could still be refunded
     * after every other reservation and the earlier rows' claims, so a stale claim is replaced as well as an excessive one.
     */
    public Map<String, Long> claims() {
        var claims = new LinkedHashMap<String, Long>();
        var current = withoutClaims();
        for (var id : interventions) {
            long owed = Math.max(0, current.freeFor(id));
            claims.put(id, owed);
            if (owed > 0) { current = current.withClaims(Map.of(id, owed)); }
        }
        return claims;
    }

    private PaymentIntentLedger withoutClaims() {
        return new PaymentIntentLedger(rows, reservations.stream().filter(r -> !r.claim()).toList(), interventions);
    }

    public PaymentIntentLedger withClaims(Map<String, Long> claims) {
        var replaced = new ArrayList<>(reservations.stream().filter(r -> !(r.claim() && claims.containsKey(r.id()))).toList());
        claims.forEach((id, owed) -> { if (owed > 0) { replaced.add(Reservation.intervention(id, owed)); } });
        return new PaymentIntentLedger(rows, replaced, interventions);
    }

    public CapturedPaymentLedger balance() {
        return new CapturedPaymentLedger(rows.stream().mapToLong(Row::captured).sum(), rows.stream().mapToLong(Row::refunded).sum(),
                reservations.stream().mapToLong(Reservation::amount).sum(), rows.stream().mapToLong(Row::credited).sum());
    }

    /** Settled excess only: pending refunds may reduce a credit, but never constitute money already repaid. */
    public long overpaid() {
        var balance = balance();
        return Math.max(0, balance.refunded() + balance.credited() - balance.captured());
    }

    /** All mutable credits absorb the intent's excess before an immutable billed credit can cause an intervention. */
    public Map<String, Long> creditReductions() {
        // Claims only take what is left: an obsolete claim can never consume a credit (they are refreshed afterwards).
        long excess = Math.max(0, -withoutClaims().balance().free());
        var reductions = new LinkedHashMap<String, Long>();
        for (var row : rows) {
            long reduction = row.billed() ? 0 : Math.min(row.credited(), excess);
            if (reduction > 0) { reductions.put(row.id(), reduction); excess -= reduction; }
        }
        return reductions;
    }

    public CapturedPaymentLedger share(String id) {
        var row = rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
        return new CapturedPaymentLedger(row.captured(), row.refunded(), reservationShares(null).getOrDefault(id, 0L), row.credited());
    }

    /** A row cannot spend another row's share, or manufacture an allowance larger than the intent's. */
    public long freeFor(String id) { return Math.min(share(id).free(), balance().free()); }

    /** Replacing a manual reservation must still respect every other row's claim on the intent. */
    public PaymentIntentLedger withoutReservation(String id) {
        return new PaymentIntentLedger(rows, reservations.stream().filter(reservation -> !reservation.id().equals(id)).toList(), interventions);
    }

    /**
     * An operation's settlement prefers its target. An unlinked dashboard refund (no operation) is how an admin repays an
     * intervention (N-55), so it is placed as if the claims were absent, on the rows in intervention first (E111).
     */
    public Map<String, Long> settlementShares(long amount, String targetId, String operationId) {
        var capacity = capacity();
        if (capacity.values().stream().mapToLong(Long::longValue).sum() < amount) { throw new ApiException(ErrorCode.REFUND_EXCEEDS_PAID); }
        var reserved = (operationId == null ? withoutClaims() : this).reservationShares(operationId);
        var preferred = new ArrayList<String>();
        if (targetId != null) { preferred.add(targetId); }
        if (operationId == null) { preferred.addAll(interventions); }
        var unreserved = new LinkedHashMap<String, Long>();
        capacity.forEach((id, value) -> unreserved.put(id, Math.max(0, value - reserved.getOrDefault(id, 0L))));
        var shares = new LinkedHashMap<String, Long>();
        long remaining = allocate(amount, preferred, unreserved, shares);
        // Stripe can settle before a local command is submitted; preserve that command and reallocate its reservation.
        capacity.replaceAll((id, value) -> value - shares.getOrDefault(id, 0L));
        allocate(remaining, preferred, capacity, shares);
        return shares;
    }

    private Map<String, Long> capacity() {
        var capacity = new LinkedHashMap<String, Long>();
        rows.forEach(row -> capacity.put(row.id(), Math.max(0, row.captured() - row.refunded())));
        return capacity;
    }

    private Map<String, Long> reservationShares(String excluding) {
        var capacity = capacity();
        var shares = new LinkedHashMap<String, Long>();
        // Claims come last, on their own rows; an unlinked pending refund is reserved on the rows in intervention first.
        var ordered = new ArrayList<>(reservations.stream().filter(r -> !r.claim()).toList());
        ordered.addAll(reservations.stream().filter(Reservation::claim).toList());
        for (var reservation : ordered) {
            var destination = reservation.id().equals(excluding) ? new LinkedHashMap<String, Long>() : shares;
            var preferred = reservation.targetId() != null ? List.of(reservation.targetId()) : interventions;
            long remaining = allocate(reservation.amount(), preferred, capacity, destination);
            // A dashboard refund can overtake an unsubmitted command. Keep its whole amount reserved until it fails.
            if (remaining > 0 && !rows.isEmpty()) {
                String id = capacity.containsKey(reservation.targetId()) ? reservation.targetId() : rows.getFirst().id();
                destination.merge(id, remaining, Long::sum);
            }
        }
        return shares;
    }

    /** Prefer the given rows, in their order, within each tier: uncredited funds, unbilled credit, then billed credit. */
    private long allocate(long amount, List<String> preferred, Map<String, Long> capacity, Map<String, Long> shares) {
        var order = new ArrayList<>(rows);
        order.sort(Comparator.comparing(row -> preferred.contains(row.id()) ? preferred.indexOf(row.id()) : preferred.size()));
        for (int tier = 0; tier < 3; tier++) {
            for (var row : order) {
                long protectedCredit = tier == 0 || tier == 1 && row.billed() ? row.credited() : 0;
                long part = Math.min(amount, Math.max(0, capacity.get(row.id()) - protectedCredit));
                shares.merge(row.id(), part, Long::sum);
                capacity.compute(row.id(), (id, value) -> value - part);
                amount -= part;
            }
        }
        return amount;
    }
}
