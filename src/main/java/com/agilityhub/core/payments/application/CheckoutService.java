package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class CheckoutService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CheckoutService.class);
    public record Result(String checkoutUrl,String checkoutSessionId) { }
    private final SignupPaymentAccess members;private final UpfrontPayments payments;private final CensusClubSettings clubs;
    private final ClubConfigService configs;private final SignupCheckoutRepository sessions;private final ObjectProvider<PaymentProvider> gateways;
    private final IdentityTransactions transactions;private final Clock clock;private final com.agilityhub.core.shared.application.IcuMessageSource messages;
    public CheckoutService(SignupPaymentAccess members,UpfrontPayments payments,CensusClubSettings clubs,ClubConfigService configs,
            SignupCheckoutRepository sessions,ObjectProvider<PaymentProvider> gateways,IdentityTransactions transactions,Clock clock,com.agilityhub.core.shared.application.IcuMessageSource messages) {
        this.members=members;this.payments=payments;this.clubs=clubs;this.configs=configs;this.sessions=sessions;this.gateways=gateways;this.transactions=transactions;this.clock=clock;this.messages=messages;
    }
    public Result create(String memberId,String token,String success,String cancel) {
        members.authorize(memberId,token);
        if(!clubs.providerEnabled("STRIPE")) throw new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED);
        PaymentProvider gateway=gateways.getIfAvailable();if(gateway==null) throw new ApiException(ErrorCode.NOT_IMPLEMENTED);
        redirect(success);redirect(cancel);
        return transactions.run(() -> {
            members.lock();var member=members.member(memberId);
            if(!Set.of("PENDING","ACTIVE").contains(member.get("status"))) throw new ApiException(ErrorCode.INVALID_STATE);
            String currency=configs.get(TenantContext.require()).club().currency();
            var scope=members.submissions(memberId);
            var due=payments.due(memberId,scope,currency);var method=member.get("paymentMethod") instanceof Map<?,?> map?map:Map.of();
            boolean card="CARD".equals(method.get("type"));
            if(due.amountMinor()==0&&!card) throw new ApiException(ErrorCode.INVALID_STATE);
            var lines=payments.lines(memberId,scope).stream().filter(l -> l.amount().amountMinor()>l.paidAmount().amountMinor()).toList();
            if(lines.stream().anyMatch(l -> l.status().equals("CHECKOUT_PENDING"))) throw new ApiException(ErrorCode.INVALID_STATE);
            String id=UUID.randomUUID().toString();Instant expires=clock.instant().plus(Duration.ofHours(24));
            var ids=lines.stream().map(UpfrontPayments.Line::id).toList();String mode=due.amountMinor()==0?"setup":"payment";
            var request=new PaymentProvider.Request(id,TenantContext.require(),memberId,mode,lines.stream().map(l -> new PaymentProvider.Item(l.id(),messages.format("signup:payment.concept."+l.concept(),Map.of(),Locale.forLanguageTag((String)member.get("locale"))),l.amount().minus(l.paidAmount()))).toList(),
                    (String)member.get("email"),memberId,Map.of("clubId",TenantContext.require(),"memberId",memberId,"upfrontPaymentIds",ids),card?"off_session":null,success,cancel,expires);
            String url=gateway.createCheckoutSession(request);
            sessions.insert(new SignupCheckoutSession(id,TenantContext.require(),memberId,"PENDING",mode,ids,expires,null));payments.pending(memberId,ids,id);
            return new Result(url,id);
        });
    }
    private void redirect(String value) {
        try {
            var uri=URI.create(value);
            if(!"https".equals(uri.getScheme())||!clubs.appHost().equalsIgnoreCase(uri.getHost())||uri.getPort()!=-1||uri.getUserInfo()!=null) throw new IllegalArgumentException();
        } catch(IllegalArgumentException|NullPointerException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field","redirectUrl")); }
    }
    /**
     * S08 R-08-18 PAY_TO_BOOK, Mongo only and inside the caller's booking transaction: the due line (`concept = SINGLE_CLASS`,
     * `bookingId`) in CHECKOUT_PENDING and its PENDING session. The provider is asked for the session only after the commit
     * (with this `sessionId`), so a retried transaction never opens a second provider checkout.
     */
    public BookingCheckout prepareBooking(String memberId,String bookingId,UpfrontPayments.Charge charge,Instant expiresAt) {
        String paymentId=payments.createForBooking(memberId,charge,bookingId),id=UUID.randomUUID().toString();
        sessions.insert(new SignupCheckoutSession(id,TenantContext.require(),memberId,"PENDING","payment",List.of(paymentId),expiresAt,bookingId));
        payments.pending(memberId,List.of(paymentId),id);
        return new BookingCheckout(id,paymentId);
    }
    public record BookingCheckout(String sessionId,String paymentId) { }
    /**
     * The provider completed the session; `providerPaymentId` is its payment (kept on the session for S12 reconciliation).
     * A booking session completed after its `expiresAt` (or already EXPIRED) is a late completion (E34): WARN + mark, never
     * a confirmation and never an error; a provider retry keeps the first mark.
     */
    public void complete(String sessionId,String providerPaymentId,Map<String,Object> card) { finish(sessionId,true,providerPaymentId,card); }
    public void expire(String sessionId) { finish(sessionId,false,null,Map.of()); }
    private void finish(String id,boolean complete,String providerPaymentId,Map<String,Object> card) {
        transactions.run(() -> {
            members.lock();var session=sessions.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            // E34: P7 (or a failed provider call) expired the booking checkout on our side, but the provider still took the money.
            if(complete&&session.bookingId()!=null&&"EXPIRED".equals(session.status())) { lateCompletion(session,providerPaymentId,"the checkout expired");return null; }
            if(!"PENDING".equals(session.status())) return null;
            if(complete&&!session.expiresAt().isAfter(clock.instant())) {
                if(session.bookingId()==null) throw new ApiException(ErrorCode.INVALID_STATE);
                // E34: past `bookings.paymentPendingMinutes` the booking is never confirmed. The session expires as P7 would expire
                // it (line CANCELLED + UpfrontPaymentFailed) and keeps the mark, also when the club already cancelled the booking.
                if(!sessions.finish(id,"EXPIRED",providerPaymentId)) return null;
                payments.checkout(session.memberId(),id,false);
                lateCompletion(session,providerPaymentId,"the checkout deadline passed");
                return null;
            }
            if(!sessions.finish(id,complete?"COMPLETE":"EXPIRED",complete?providerPaymentId:null)) return null;
            payments.checkout(session.memberId(),id,complete);
            if(complete&&session.bookingId()==null) members.card(session.memberId(),card); // a booking payment never changes the payment method
            return null;
        });
    }
    /**
     * E34 (S15 R-15-17): the provider completed a PAY_TO_BOOK checkout after its booking was cancelled on our side, so the
     * money was taken but no booking stands. The session keeps a reconciliation mark (`lateCompletionAt`, `providerPaymentId`)
     * for the S12 refund (E8-T04 step 12); nothing is settled and no event is emitted here.
     */
    public void bookingCancelledBeforeCompletion(String sessionId) {
        sessions.findById(sessionId).ifPresent(session -> lateCompletion(session,session.providerPaymentId(),"the booking was cancelled"));
    }
    private void lateCompletion(SignupCheckoutSession session,String providerPaymentId,String cause) {
        if(sessions.markLateCompletion(session.id(),providerPaymentId,clock.instant())) {
            LOG.warn("Late provider completion to refund: {} checkoutSessionId={} bookingId={} providerPaymentId={} clubId={}",
                    cause,session.id(),session.bookingId(),providerPaymentId,session.clubId());
        }
    }
}
