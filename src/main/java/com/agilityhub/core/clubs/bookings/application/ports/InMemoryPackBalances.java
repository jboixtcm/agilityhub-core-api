package com.agilityhub.core.clubs.bookings.application.ports;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/**
 * Local/test stand-in for S12 pack balances (E8 replaces it). A balance exists only for the dogs a test or the demo
 * seed opens; other dogs have no pack. Changes are undone when the surrounding Mongo transaction rolls back, so a
 * retried booking transaction never consumes twice.
 */
@Component
@Profile({"local", "test"})
public class InMemoryPackBalances implements PackBalancePort {
    private final Map<String, Balance> balances = new ConcurrentHashMap<>();
    public void open(String memberId, String dogId, int total, int consumed, LocalDate expiresOn) {
        balances.put(key(memberId, dogId), new Balance(UUID.randomUUID().toString(), total, consumed, total - consumed, expiresOn));
    }
    public void clear() { balances.clear(); }
    @Override public Optional<Balance> balance(String memberId, String dogId) { return Optional.ofNullable(balances.get(key(memberId, dogId))); }
    @Override public String consume(String memberId, String dogId, String bookingId) {
        return change(memberId, dogId, +1, null);
    }
    @Override public String refund(String memberId, String dogId, String bookingId, LocalDate today) {
        return change(memberId, dogId, -1, today);
    }
    private String change(String memberId, String dogId, int delta, LocalDate today) {
        String key = key(memberId, dogId); var before = balances.get(key);
        if (before == null || today != null && before.expiresOn() != null && before.expiresOn().isBefore(today)) { return null; }
        int consumed = Math.max(0, Math.min(before.total(), before.consumed() + delta));
        balances.put(key, new Balance(before.id(), before.total(), consumed, before.total() - consumed, before.expiresOn()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) { balances.put(key, before); } }
            });
        }
        return UUID.randomUUID().toString();
    }
    private static String key(String memberId, String dogId) { return TenantContext.require() + ":" + memberId + ":" + dogId; }
}
