package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

@Service
public class CheckoutService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CheckoutService.class);
    public record Result(String checkoutUrl,String checkoutSessionId) { }
    private final SignupPaymentAccess members;private final UpfrontPayments payments;private final CensusClubSettings clubs;
    private final ClubConfigService configs;private final SignupCheckoutRepository sessions;private final ObjectProvider<PaymentProvider> gateways;
    private final IdentityTransactions transactions;private final Clock clock;private final com.agilityhub.core.shared.application.IcuMessageSource messages;
    private final TransactionTemplate outside;
    public CheckoutService(SignupPaymentAccess members,UpfrontPayments payments,CensusClubSettings clubs,ClubConfigService configs,
            SignupCheckoutRepository sessions,ObjectProvider<PaymentProvider> gateways,IdentityTransactions transactions,Clock clock,com.agilityhub.core.shared.application.IcuMessageSource messages,
            PlatformTransactionManager manager) {
        this.members=members;this.payments=payments;this.clubs=clubs;this.configs=configs;this.sessions=sessions;this.gateways=gateways;this.transactions=transactions;this.clock=clock;this.messages=messages;
        this.outside=new TransactionTemplate(manager);this.outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
    /**
     * `POST /checkout-sessions` (A3-06): the session and its `CHECKOUT_PENDING` rows commit in the signup's retried transaction
     * (a concurrent census write never gives a 500), then the provider is asked for the session outside that unit, as
     * {@link #prepareBooking} does. A provider failure expires the session again on our side and releases the key.
     * <p>
     * E5-T28 round 2: the 201 ({@code answer}) is stored with the Idempotency-Key only while the session is still `PENDING`,
     * in a short transaction that meets a concurrent rejection on the census lock. A rejection that committed while the
     * provider was opening the session (its expiry could not reach the provider yet) gets the provider session expired here,
     * and the route answers `409 INVALID_STATE`, as for a rejected signup: no checkout URL goes out (review #2). When the
     * answer cannot be stored, the session stays open under this request's reference ({@link IdempotentOperation#reference}):
     * a retry with the same key finds it and asks the provider again for the same session, which the provider answers
     * idempotently by `sessionId`, so the same checkout comes back and no second one opens (CONVENCIONS_API §7, review #3).
     */
    public Result create(String memberId,String token,String success,String cancel,java.util.function.Function<Result,byte[]> answer) {
        members.authorize(memberId,token);
        if(!clubs.providerEnabled("STRIPE")) throw new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED);
        PaymentProvider gateway=gateways.getIfAvailable();if(gateway==null) throw new ApiException(ErrorCode.NOT_IMPLEMENTED);
        redirect(success);redirect(cancel);
        String reference=IdempotentOperation.reference();
        var request=members.write(() -> { IdempotentOperation.lock();return prepare(memberId,success,cancel,reference); });
        String url;
        try { url=gateway.createCheckoutSession(request); }
        catch(RuntimeException failure) {
            IdempotentOperation.release();
            abandon(memberId,request.sessionId());
            throw failure;
        }
        var result=new Result(url,request.sessionId());byte[] body=answer.apply(result);
        boolean open=members.write(() -> {
            IdempotentOperation.lock();members.lock();
            if(!sessions.pending(request.sessionId())) return false;
            IdempotentOperation.complete(201,body);return true;
        });
        if(!open) {
            expireAtProvider(request.sessionId(),request.clubId());
            throw new ApiException(ErrorCode.INVALID_STATE);
        }
        return result;
    }
    /** The provider could not open the session: it expires on our side and its rows are DUE again (a new checkout may open). */
    private void abandon(String memberId,String id) {
        members.write(() -> { members.lock();if(sessions.finish(id,"EXPIRED",null)) payments.checkout(memberId,id,false);return null; });
    }
    private PaymentProvider.Request prepare(String memberId,String success,String cancel,String reference) {
        members.lock();var member=members.member(memberId);
        if(!Set.of("PENDING","ACTIVE").contains(member.get("status"))) throw new ApiException(ErrorCode.INVALID_STATE);
        String currency=configs.get(TenantContext.require()).club().currency();
        var scope=members.submissions(memberId);
        var lines=payments.lines(memberId,scope).stream().filter(l -> l.amount().amountMinor()>l.paidAmount().amountMinor()).toList();
        var retried=reference==null?Optional.<SignupCheckoutSession>empty():sessions.openFor(memberId,reference,clock.instant());
        if(retried.isPresent()) {
            // Review #3: the same request, whose answer was lost after this session opened: the same session, asked again.
            var open=retried.get();
            return request(member,memberId,open.id(),open.mode(),lines.stream().filter(l -> open.upfrontPaymentIds().contains(l.id())).toList(),scope,success,cancel,open.expiresAt());
        }
        var due=payments.due(memberId,scope,currency);var method=member.get("paymentMethod") instanceof Map<?,?> map?map:Map.of();
        boolean card="CARD".equals(method.get("type"));
        if(due.amountMinor()==0&&!card) throw new ApiException(ErrorCode.INVALID_STATE);
        if(lines.stream().anyMatch(l -> l.status().equals("CHECKOUT_PENDING"))) throw new ApiException(ErrorCode.INVALID_STATE);
        String id=UUID.randomUUID().toString();Instant expires=clock.instant().plus(Duration.ofHours(24));
        var ids=lines.stream().map(UpfrontPayments.Line::id).toList();String mode=due.amountMinor()==0?"setup":"payment";
        var request=request(member,memberId,id,mode,lines,scope,success,cancel,expires);
        sessions.insert(new SignupCheckoutSession(id,TenantContext.require(),memberId,"PENDING",mode,ids,expires,null,null,null,reference));payments.pending(memberId,ids,id);
        return request;
    }
    private PaymentProvider.Request request(Map<String,Object> member,String memberId,String id,String mode,List<UpfrontPayments.Line> lines,List<UpfrontPayments.Submission> scope,
            String success,String cancel,Instant expires) {
        var method=member.get("paymentMethod") instanceof Map<?,?> map?map:Map.of();boolean card="CARD".equals(method.get("type"));
        var ids=lines.stream().map(UpfrontPayments.Line::id).toList();
        // R-04-26 (E3-T13): each line in the language of the submission it belongs to, never one language for the member.
        var locales=members.locales(memberId,scope);
        return new PaymentProvider.Request(id,TenantContext.require(),memberId,mode,lines.stream().map(l -> new PaymentProvider.Item(l.id(),messages.format("signup:payment.concept."+l.concept(),Map.of(),Locale.forLanguageTag(locales.get(l.submission()))),l.amount().minus(l.paidAmount()))).toList(),
                (String)member.get("email"),memberId,Map.of("clubId",TenantContext.require(),"memberId",memberId,"upfrontPaymentIds",ids),card?"off_session":null,success,cancel,expires);
    }
    /**
     * S04 R-04-23 and §5 (A3-01): a rejection closes the signup checkouts it leaves without purpose, inside the rejection's
     * transaction: those that charge a row of {@code paymentIds} (the rejected submissions' rows, which the rejection
     * cancels), and every open one when the member stops being a signup ({@code memberLeft}: a card setup included).
     * Each goes to `EXPIRED` and gives its rows back to the payable state (E5-T28 round 2, review #1, R-04-26): the rows of
     * another submission it also charged (a dog already validated with nothing paid) are `DUE` (or `PARTIAL`) again, so a
     * new checkout can charge them, and the rejection then cancels its own. The provider is asked to expire the session
     * after the commit, outside any transaction. A provider completion that still arrives takes the E34 path ({@link #complete}).
     */
    public void rejected(String memberId,Collection<String> paymentIds,boolean memberLeft) {
        for(var session:sessions.openSignup(memberId)) {
            if((memberLeft||session.upfrontPaymentIds().stream().anyMatch(paymentIds::contains))&&sessions.finish(session.id(),"EXPIRED",null)) {
                payments.checkout(memberId,session.id(),false);
                afterCommit(() -> expireAtProvider(session.id(),session.clubId()));
            }
        }
    }
    private void expireAtProvider(String sessionId,String clubId) {
        var gateway=gateways.getIfAvailable();if(gateway==null) return;
        try { gateway.expire(sessionId); }
        catch(RuntimeException failure) {
            // The session is EXPIRED on our side whatever the provider answers; a later completion is a late one (E34).
            LOG.warn("Provider expiry failed: checkoutSessionId={} clubId={} error={}",sessionId,clubId,failure.toString());
        }
    }
    /** Runs {@code action} once the caller's transaction committed, outside it (never inside a retried unit of work). */
    private void afterCommit(Runnable action) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()) { action.run();return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { outside.executeWithoutResult(status -> action.run()); }
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
     * A booking session completed after its `expiresAt`, any session already EXPIRED on our side, and a signup session whose
     * rows are no longer `CHECKOUT_PENDING` (a rejection cancelled them) are late completions (E34, A3-01): WARN + mark,
     * never a confirmation, never a `PAID` row, never a card on the member, and never an error; a provider retry keeps the
     * first mark.
     */
    public void complete(String sessionId,String providerPaymentId,Map<String,Object> card) {
        transactions.run(() -> {
            members.lock();var session=sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            completed(session,providerPaymentId,card);
            return null;
        });
    }
    private void completed(SignupCheckoutSession session,String providerPaymentId,Map<String,Object> card) {
        String id=session.id();
        // E34: P7, a failed provider call or a rejection expired the checkout on our side, but the provider still took the money.
        if("EXPIRED".equals(session.status())) { lateCompletion(session,providerPaymentId,"the checkout expired");return; }
        if(!"PENDING".equals(session.status())) return;
        if(session.bookingId()==null&&!session.upfrontPaymentIds().isEmpty()&&!payments.checkoutPending(session.memberId(),id)) {
            if(sessions.finish(id,"EXPIRED",providerPaymentId)) lateCompletion(session,providerPaymentId,"its signup rows were closed");
            return;
        }
        if(!session.expiresAt().isAfter(clock.instant())) {
            if(session.bookingId()==null) throw new ApiException(ErrorCode.INVALID_STATE);
            // E34: past `bookings.paymentPendingMinutes` the booking is never confirmed. The session expires as P7 would expire
            // it (line CANCELLED + UpfrontPaymentFailed) and keeps the mark, also when the club already cancelled the booking.
            if(!sessions.finish(id,"EXPIRED",providerPaymentId)) return;
            payments.checkout(session.memberId(),id,false);
            lateCompletion(session,providerPaymentId,"the checkout deadline passed");
            return;
        }
        if(!sessions.finish(id,"COMPLETE",providerPaymentId)) return;
        payments.checkout(session.memberId(),id,true);
        if(session.bookingId()==null) members.card(session.memberId(),card); // a booking payment never changes the payment method
    }
    /**
     * The provider expired the session: a `PENDING` one goes to `EXPIRED` and gives its rows back (a signup row to its payable
     * state, a booking row `CANCELLED`, {@link UpfrontPayments#checkout}); any other state is left as it is. Its own path, so an
     * expiry (which `POST /checkout-sessions` asks for, E5-T28) never reaches the member's card.
     */
    public void expire(String sessionId) {
        transactions.run(() -> {
            members.lock();var session=sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if(sessions.finish(sessionId,"EXPIRED",null)) payments.checkout(session.memberId(),sessionId,false);
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
