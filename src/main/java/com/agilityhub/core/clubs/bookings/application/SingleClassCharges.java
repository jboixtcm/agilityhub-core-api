package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.payments.application.PaymentProvider;
import com.agilityhub.core.payments.application.UpfrontPayments;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Default {@link SingleClassChargePort} over the existing `payments.application` code until S12 WP-12-D (E8):
 * terms from the owner's plan (`type = SINGLE_CLASS`, `singleClass.chargeMode`) and price; a PAY_TO_BOOK checkout is
 * one `UpfrontPayment` line (`concept = SINGLE_CLASS`) plus `PaymentProvider.createCheckoutSession` carrying the
 * `bookingId`, expiring after `bookings.paymentPendingMinutes`. `FakeCheckoutGateway` serves local/test.
 */
public class SingleClassCharges implements SingleClassChargePort {
    private final BookingMemberAccess census; private final UpfrontPayments payments; private final ObjectProvider<PaymentProvider> gateways;
    private final BookingContext context; private final CensusClubSettings clubs;
    public SingleClassCharges(BookingMemberAccess census, UpfrontPayments payments, ObjectProvider<PaymentProvider> gateways, BookingContext context, CensusClubSettings clubs) {
        this.census = census; this.payments = payments; this.gateways = gateways; this.context = context; this.clubs = clubs;
    }
    @Override public Optional<Terms> terms(String ownerMemberId) {
        var plan = census.planTerms(ownerMemberId);
        if (!"SINGLE_CLASS".equals(plan.type()) || plan.chargeMode() == null || plan.price() == null) { return Optional.empty(); }
        return Optional.of(new Terms(ChargeMode.valueOf(plan.chargeMode()), plan.price()));
    }
    @Override public Checkout checkout(String ownerMemberId, String dogId, String bookingId, Money price, String description) {
        var gateway = gateways.getIfAvailable();
        if (gateway == null) { throw new ApiException(ErrorCode.NOT_IMPLEMENTED); }
        String paymentId = payments.createOne(ownerMemberId, new UpfrontPayments.Charge("SINGLE_CLASS", dogId, price));
        String sessionId = UUID.randomUUID().toString(), base;
        try { base = "https://" + clubs.appHost() + "/reserves/" + bookingId; } catch (ApiException noVerifiedHost) { base = "/reserves/" + bookingId; }
        var member = census.member(ownerMemberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var request = new PaymentProvider.Request(sessionId, TenantContext.require(), ownerMemberId, "payment",
                List.of(new PaymentProvider.Item(paymentId, description, price)), member.email(), bookingId,
                Map.of("clubId", TenantContext.require(), "memberId", ownerMemberId, "bookingId", bookingId, "upfrontPaymentIds", List.of(paymentId)),
                null, base + "?payment=success", base + "?payment=cancel",
                context.now().plus(Duration.ofMinutes(context.integer("bookings.paymentPendingMinutes"))));
        String url = gateway.createCheckoutSession(request);
        payments.pending(ownerMemberId, List.of(paymentId), sessionId);
        return new Checkout(sessionId, url);
    }
}
