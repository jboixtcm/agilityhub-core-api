package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.payments.application.PackBalanceService;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** E90: the consuming context adapts S12 without a reverse dependency. */
@Service
public class PaymentsPackBalanceAdapter implements PackBalancePort {
    private final PackBalanceService packs;
    private final BookingContext context;
    public PaymentsPackBalanceAdapter(PackBalanceService packs, BookingContext context) { this.packs = packs; this.context = context; }
    @Override public Optional<Balance> balance(String memberId, String dogId) { return balance(memberId, dogId, context.today()); }
    @Override public Optional<Balance> balance(String memberId, String dogId, LocalDate date) {
        return packs.balance(memberId, dogId, date).map(p -> new Balance(p.id(), p.total(), p.consumed(), p.remaining(), p.expiresOn()));
    }
    @Override public String consume(String memberId, String dogId, String bookingId) { return consume(memberId, dogId, bookingId, context.today()); }
    @Override public String consume(String memberId, String dogId, String bookingId, LocalDate date) { return packs.consume(memberId, dogId, bookingId, date); }
    @Override public String refund(String memberId, String dogId, String bookingId, LocalDate today) { return packs.refund(bookingId); }
}
