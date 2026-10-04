package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.domain.CheckoutStatus;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** S12 extensions use the same session, upfront rows and provider as S04. The request is frozen before the provider call. */
@Service
public class PaymentCheckouts {
    @org.springframework.beans.factory.annotation.Autowired private BookingOwnerAccess bookings;
    private final SignupCheckoutRepository sessions; private final UpfrontPaymentRepository rows; private final UpfrontPayments payments;
    private final CheckoutService signup; private final SignupPaymentAccess members; private final BillingTransactions tx;
    private final PaymentProviderRegistry provider; private final ClubConfigService configs; private final Clock clock;
    public PaymentCheckouts(SignupCheckoutRepository sessions, UpfrontPaymentRepository rows, UpfrontPayments payments, CheckoutService signup,
            SignupPaymentAccess members, BillingTransactions tx, PaymentProviderRegistry provider, ClubConfigService configs, Clock clock) {
        this.sessions = sessions; this.rows = rows; this.payments = payments; this.signup = signup; this.members = members; this.tx = tx;
        this.provider = provider; this.configs = configs; this.clock = clock;
    }
    public CheckoutService.Result create(String memberId, String bookingId, List<String> paymentIds, boolean setup, String success, String cancel,
            Function<CheckoutService.Result, byte[]> answer) {
        provider.require(setup ? PaymentProvider.Capability.CARD_SETUP : PaymentProvider.Capability.CHECKOUT);
        signup.redirect(success); signup.redirect(cancel);
        if (bookingId != null) {
            if (bookings.cancelled(bookingId)) { throw new ApiException(ErrorCode.INVALID_STATE); }
            if (paymentIds != null) {
                for (String id : paymentIds) {
                    var row = rows.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
                    if (!row.memberId().equals(memberId) || !bookingId.equals(row.bookingId())) { throw new ApiException(ErrorCode.NOT_FOUND); }
                }
            }
            var existing = bookings.checkout(bookingId);
            if (existing.isPresent()) {
                var result = new CheckoutService.Result(existing.get().url(), existing.get().sessionId());
                return tx.keyed(201, () -> result, answer);
            }
        }
        var request = tx.run(() -> {
            IdempotentOperation.lock(); members.lock();
            var prior = sessions.openFor(memberId, IdempotentOperation.reference(), clock.instant());
            if (prior.isPresent()) { return prior.get().providerRequest(); }
            List<UpfrontPayment> selected = setup ? List.of() : paymentIds == null || paymentIds.isEmpty()
                    ? rows.member(memberId).stream().filter(p -> Objects.equals(bookingId, p.bookingId())).toList()
                    : paymentIds.stream().distinct().map(id -> rows.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))).toList();
            if (!setup && selected.isEmpty()) { throw new ApiException(ErrorCode.INVALID_STATE); }
            for (var payment : selected) {
                if (!payment.memberId().equals(memberId)) { throw new ApiException(ErrorCode.NOT_FOUND); }
                if (!Set.of("DUE", "PARTIAL").contains(payment.status())) { throw new ApiException(ErrorCode.INVALID_STATE); }
            }
            String id = UUID.randomUUID().toString(); var expires = clock.instant().plus(Duration.ofHours(24));
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("clubId", TenantContext.require()); metadata.put("memberId", memberId);
            metadata.put("currency", configs.get(TenantContext.require()).club().currency());
            metadata.put("upfrontPaymentIds", selected.stream().map(UpfrontPayment::id).toList());
            var lines = selected.stream().map(p -> new PaymentProvider.Item(p.id(), p.concept(), p.amountDue().minus(p.amountPaid()))).toList();
            var requestValue = new PaymentProvider.Request(id, TenantContext.require(), memberId, setup ? "setup" : "payment", lines,
                    null, bookingId == null ? memberId : bookingId, metadata, setup ? null : "off_session", success, cancel, expires);
            sessions.insert(new SignupCheckoutSession(id, TenantContext.require(), memberId, "PENDING", requestValue.mode(),
                    selected.stream().map(UpfrontPayment::id).toList(), expires, bookingId, null, null, IdempotentOperation.reference(), requestValue));
            if (setup) { sessions.markStandaloneCardSetup(id); }
            payments.pending(memberId, selected.stream().map(UpfrontPayment::id).toList(), id); return requestValue;
        });
        var result = new CheckoutService.Result(provider.createCheckoutSession(request), request.sessionId());
        tx.keyed(201, () -> { if (!sessions.answered(request.sessionId())) { throw new ApiException(ErrorCode.INVALID_STATE); } return result; }, answer);
        return result;
    }
    public CheckoutStatus status(String id) {
        var row = sessions.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return "COMPLETE".equals(row.status()) ? CheckoutStatus.PAID : "EXPIRED".equals(row.status()) ? CheckoutStatus.EXPIRED : CheckoutStatus.PENDING;
    }
}
