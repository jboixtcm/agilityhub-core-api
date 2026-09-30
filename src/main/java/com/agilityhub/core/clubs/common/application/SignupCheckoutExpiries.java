package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.application.ports.LapsedCheckoutsPort;
import com.agilityhub.core.payments.application.CheckoutService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Default {@link LapsedCheckoutsPort} over the existing `payments.application` code until S12 WP-12-D (E8):
 * {@link CheckoutService#lapsedSignupCheckouts} plans, {@link CheckoutService#expireLapsed} expires one session under the
 * census lock, in P5's item transaction.
 */
@Component
public class SignupCheckoutExpiries implements LapsedCheckoutsPort {
    private final CheckoutService checkouts;
    public SignupCheckoutExpiries(CheckoutService checkouts) { this.checkouts = checkouts; }
    @Override public List<Lapsed> lapsed(Instant now) {
        return checkouts.lapsedSignupCheckouts(now).stream().map(lapsed -> new Lapsed(lapsed.sessionId(), lapsed.expiresAt())).toList();
    }
    @Override public boolean expire(String checkoutSessionId, Instant now) { return checkouts.expireLapsed(checkoutSessionId, now); }
}
