package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * S12 R-12-21 (E8-T01 round 2): a club's `paymentProviders.STRIPE` as stored, read by the club id from the committed club
 * document (no tenant, no cache: the Stripe webhook runs outside the tenant filter, and a rotated secret applies to the next
 * delivery). The secrets stay encrypted here ({@link StripeProviderSettings}); only S12 decrypts them where it uses them.
 */
@Service
public class ClubPaymentProviders {
    private final ClubRepository clubs;
    public ClubPaymentProviders(ClubRepository clubs) { this.clubs = clubs; }
    /** The club's Stripe settings; empty when no club has that id (an absent provider is an empty, disabled one). */
    public Optional<StripeProviderSettings> stripe(String clubId) {
        return clubs.findById(clubId).map(club -> StripeProviderSettings.of(club.paymentProviders()));
    }
}
