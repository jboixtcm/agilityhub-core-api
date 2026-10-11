package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.*;

/**
 * E105: one immutable view of a captured intent, loaded under its transaction fence. The rows are projections of the
 * capture and settled refunds; reservations include local commands and unlinked dashboard refunds exactly once.
 * Every decision uses this same view. In particular, a row's historical refund placement cannot protect its neighbour's
 * unbilled credit from absorption. Rebuild the view after persisting a decision, before deciding anything else.
 */
public record PaymentIntentLedger(List<Row> rows, List<Reservation> reservations) {
    public record Row(String id, long captured, long refunded, long credited, boolean billed) { }
    public record Reservation(String id, String targetId, long amount) { }

    public PaymentIntentLedger {
        rows = List.copyOf(rows);
        reservations = List.copyOf(reservations);
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
        long excess = Math.max(0, -balance().free());
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

    public Map<String, Long> settlementShares(long amount, String targetId, String operationId) {
        var capacity = capacity();
        if (capacity.values().stream().mapToLong(Long::longValue).sum() < amount) { throw new ApiException(ErrorCode.REFUND_EXCEEDS_PAID); }
        var reserved = reservationShares(operationId);
        var unreserved = new LinkedHashMap<String, Long>();
        capacity.forEach((id, value) -> unreserved.put(id, Math.max(0, value - reserved.getOrDefault(id, 0L))));
        var shares = new LinkedHashMap<String, Long>();
        long remaining = allocate(amount, targetId, unreserved, shares);
        // Stripe can settle before a local command is submitted; preserve that command and reallocate its reservation.
        capacity.replaceAll((id, value) -> value - shares.getOrDefault(id, 0L));
        allocate(remaining, targetId, capacity, shares);
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
        for (var reservation : reservations) {
            var destination = reservation.id().equals(excluding) ? new LinkedHashMap<String, Long>() : shares;
            long remaining = allocate(reservation.amount(), reservation.targetId(), capacity, destination);
            // A dashboard refund can overtake an unsubmitted command. Keep its whole amount reserved until it fails.
            if (remaining > 0 && !rows.isEmpty()) {
                String id = capacity.containsKey(reservation.targetId()) ? reservation.targetId() : rows.getFirst().id();
                destination.merge(id, remaining, Long::sum);
            }
        }
        return shares;
    }

    /** Prefer the command's row within each tier: uncredited funds, unbilled credit, then billed credit. */
    private long allocate(long amount, String preferred, Map<String, Long> capacity, Map<String, Long> shares) {
        var order = new ArrayList<>(rows);
        order.sort(Comparator.comparing(row -> !row.id().equals(preferred)));
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
