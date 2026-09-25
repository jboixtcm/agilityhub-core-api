package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.domain.SignupPaymentEvent;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class UpfrontPayments {
    public record Line(String id,String concept,String dogId,Money amount,Money paidAmount,String status,String provider) { }
    public record Charge(String concept,String dogId,Money amount) { }
    /**
     * One pending dog of a signup and the submission that created it (S04 §5, E3-T08). The rows of a signup are the rows of
     * these pairs only: the rows of an earlier submission for the same dog (a readmission, R-04-06/07) are history.
     * A `null` submission matches the rows written before submissions existed.
     */
    public record Submission(String dogId,String submissionId) { }
    private final UpfrontPaymentRepository repository;
    private final EventPublisher events;
    private final Clock clock;
    public UpfrontPayments(UpfrontPaymentRepository repository,EventPublisher events,Clock clock) { this.repository=repository;this.events=events;this.clock=clock; }
    public List<Line> lines(String memberId,List<Submission> scope) {
        return selected(memberId,scope).stream().filter(p -> !Set.of("CANCELLED","REFUNDED").contains(p.status())).map(this::line).toList();
    }
    private List<UpfrontPayment> selected(String memberId,List<Submission> scope) {
        // S08 PAY_TO_BOOK lines belong to their booking: the signup flows never list, charge, replace or cancel them.
        // A null scope reads every signup row of the member (only the S08 isolation check uses it).
        return repository.member(memberId).stream().filter(p -> p.bookingId()==null && (scope==null || scope.contains(new Submission(p.dogId(),p.submissionId()))))
                .sorted(Comparator.comparing(UpfrontPayment::createdAt).thenComparing(p -> "ENTRY_FEE".equals(p.concept()) ? 0 : 1).thenComparing(UpfrontPayment::id)).toList();
    }
    private Line line(UpfrontPayment p) { return new Line(p.id(),p.signupConcept()==null?p.concept():p.signupConcept(),p.dogId(),p.amountDue(),p.amountPaid(),p.status(),p.provider()); }
    /** The due line of an S08 PAY_TO_BOOK booking (`concept = SINGLE_CLASS`, `bookingId`); returns its id for the checkout. */
    public String createForBooking(String memberId,Charge charge,String bookingId) {
        String id=UUID.randomUUID().toString();
        repository.insert(new UpfrontPayment(id,TenantContext.require(),memberId,charge.dogId(),charge.concept(),charge.concept(),charge.amount(),
                new Money(0,charge.amount().currency()),"DUE",null,null,clock.instant(),null,bookingId,null,null));
        return id;
    }
    /** The rows of one submission (`POST /signup`, `POST /me/dogs/signup`); every row carries its `submissionId`. */
    public void create(String memberId,String submissionId,List<Charge> charges) {
        for (var charge:charges) {
            repository.insert(new UpfrontPayment(UUID.randomUUID().toString(),TenantContext.require(),memberId,charge.dogId(),
                    "ADDITIONAL_DOG_FEE".equals(charge.concept())?"OTHER":charge.concept(),charge.concept(),charge.amount(),new Money(0,charge.amount().currency()),
                    "DUE",null,null,clock.instant(),null,null,submissionId,null));
        }
    }
    public Money due(String memberId,List<Submission> scope,String currency) { return due(lines(memberId,scope),currency); }
    private static Money due(List<Line> lines,String currency) {
        Money amount=new Money(0,currency);
        for (var line:lines) { amount=amount.plus(line.amount().minus(line.paidAmount())); }
        return amount;
    }
    public Money paid(String memberId,List<Submission> scope,String currency) { return paid(lines(memberId,scope),currency); }
    private static Money paid(List<Line> lines,String currency) {
        Money amount=new Money(0,currency); for (var line:lines) { amount=amount.plus(line.paidAmount()); } return amount;
    }
    public void allocate(String memberId,List<Submission> scope,Money amount) {
        if (amount.amountMinor()<0) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        Money due=due(memberId,scope,amount.currency());
        if (amount.amountMinor()>due.amountMinor()) { throw new ApiException(ErrorCode.UPFRONT_AMOUNT_EXCEEDS_DUE); }
        long remaining=amount.amountMinor();
        for (var p:selected(memberId,scope)) {
            if (!Set.of("DUE","PARTIAL").contains(p.status()) || remaining==0) { continue; }
            long allocated=Math.min(remaining,p.amountDue().minus(p.amountPaid()).amountMinor()); remaining-=allocated;
            Money paid=p.amountPaid().plus(new Money(allocated,amount.currency()));
            repository.update(state(p,paid.equals(p.amountDue())?"PAID":"PARTIAL",paid,"MANUAL",null));
            // CATALEG_ESDEVENIMENTS (E3-T10): the payload names the member, like Succeeded/Failed.
            emit("UpfrontPaymentRecorded",p,Map.of("paymentId",p.id(),"memberId",p.memberId(),"concept",p.concept(),"provider","MANUAL","amountPaid",new Money(allocated,amount.currency())));
        }
        if (remaining>0) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }
    /**
     * The rows a plan change would leave: what {@link #replace} writes, without writing (the D2 `dryRun`). `corrections` are
     * the `PAID` rows that record what each closed `PARTIAL` row had received (E39b); `paidExceedsQuote` is what was paid
     * beyond the new quote (`null` when nothing exceeds it; the refund is S12's).
     */
    public record Replacement(List<Line> kept,List<Line> cancelled,List<Line> corrections,List<Charge> created,boolean checkoutPending,Money paidExceedsQuote) {
        public List<Line> lines() {
            var result=new ArrayList<>(kept);result.addAll(corrections);
            for (var charge:created) { result.add(new Line(null,charge.concept(),charge.dogId(),charge.amount(),new Money(0,charge.amount().currency()),"DUE",null)); }
            return result;
        }
        public Money due(String currency) { return UpfrontPayments.due(lines(),currency); }
        public Money paid(String currency) { return UpfrontPayments.paid(lines(),currency); }
    }
    /**
     * S04 §5 and rulings E39/E39b: a plan change never rewrites an amount. It cancels the `DUE` rows and creates the new
     * ones. `PAID` rows stay as they are. A `PARTIAL` row is closed: it goes to `CANCELLED` with its amounts untouched, and
     * a new `PAID` row (`amountDue = amountPaid` = what was received, `correctionOf` = the closed row) records the money.
     * The new rows are the new quote minus everything paid, so the outstanding total is «new quote − paid»; what was paid
     * beyond the new quote is `paidExceedsQuote`, and no row is created for it. A `CHECKOUT_PENDING` row makes the change
     * impossible (`checkoutPending`; {@link #replace} answers 409).
     */
    public Replacement replacement(String memberId,List<Submission> scope,List<Charge> charges) {
        var kept=new ArrayList<Line>();var cancelled=new ArrayList<Line>();var corrections=new ArrayList<Line>();boolean pending=false;long covered=0;String currency=null;
        for (var p:selected(memberId,scope)) {
            currency=p.amountDue().currency();
            switch (p.status()) {
                case "CANCELLED","REFUNDED" -> { }
                case "DUE" -> cancelled.add(line(p));
                case "CHECKOUT_PENDING" -> { pending=true; kept.add(line(p)); }
                case "PARTIAL" -> { cancelled.add(line(p)); corrections.add(line(correction(p))); covered+=p.amountPaid().amountMinor(); }
                default -> { kept.add(line(p)); covered+=p.amountPaid().amountMinor(); }
            }
        }
        var created=new ArrayList<Charge>();
        for (var charge:charges) {
            long consumed=Math.min(covered,charge.amount().amountMinor()); covered-=consumed;
            if (charge.amount().amountMinor()>consumed) { created.add(new Charge(charge.concept(),charge.dogId(),new Money(charge.amount().amountMinor()-consumed,charge.amount().currency()))); }
        }
        return new Replacement(List.copyOf(kept),List.copyOf(cancelled),List.copyOf(corrections),List.copyOf(created),pending,covered>0?new Money(covered,currency):null);
    }
    public Replacement replace(String memberId,List<Submission> scope,List<Charge> charges) {
        var replacement=replacement(memberId,scope,charges);
        if (replacement.checkoutPending()) { throw new ApiException(ErrorCode.INVALID_STATE,Map.of("reason","CHECKOUT_PENDING")); }
        var cancelled=replacement.cancelled().stream().map(Line::id).toList();
        for (var p:selected(memberId,scope)) { if (cancelled.contains(p.id())) { close(p); } }
        for (var charge:replacement.created()) {
            // The new row belongs to the submission of its dog; a legacy scope entry has none (`null`).
            String submission=scope.stream().filter(s -> s.dogId().equals(charge.dogId())).findFirst().map(Submission::submissionId).orElse(null);
            create(memberId,submission,List.of(charge));
        }
        return replacement;
    }
    /** R-04-23: the open rows are cancelled and a `PARTIAL` row leaves its `PAID` correction (E39b). `true` = money was received. */
    public boolean reject(String memberId,List<Submission> scope) {
        boolean paid=false;
        for(var p:selected(memberId,scope)) {
            if ("PAID".equals(p.status())) { paid=true; }
            else if ("PARTIAL".equals(p.status())) { close(p); paid=true; }
            else if (Set.of("DUE","CHECKOUT_PENDING").contains(p.status())) close(p);
        }
        return paid;
    }
    /** Cancels a row with its amounts untouched; the money a `PARTIAL` row received moves to a new `PAID` correction row. */
    private void close(UpfrontPayment p) {
        repository.update(new UpfrontPayment(p.id(),p.clubId(),p.memberId(),p.dogId(),p.concept(),p.signupConcept(),p.amountDue(),p.amountPaid(),"CANCELLED",p.provider(),
                p.checkoutSessionId(),p.createdAt(),p.paidAt(),p.bookingId(),p.submissionId(),p.correctionOf()));
        if ("PARTIAL".equals(p.status())) { repository.insert(correction(p)); }
    }
    private UpfrontPayment correction(UpfrontPayment p) {
        return new UpfrontPayment(UUID.nameUUIDFromBytes(("correction:"+p.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),p.clubId(),p.memberId(),p.dogId(),
                p.concept(),p.signupConcept(),p.amountPaid(),p.amountPaid(),"PAID",p.provider(),null,clock.instant(),p.paidAt(),p.bookingId(),p.submissionId(),p.id());
    }
    public void pending(String memberId,List<String> ids,String session) {
        for(var p:repository.member(memberId)) if(ids.contains(p.id())) {
            if(!Set.of("DUE","PARTIAL").contains(p.status())) throw new ApiException(ErrorCode.INVALID_STATE);
            repository.update(state(p,"CHECKOUT_PENDING",p.amountPaid(),"STRIPE",session));
        }
    }
    /**
     * The provider finished the session: PAID + `UpfrontPaymentSucceeded`, or an expired session puts a signup line back
     * to DUE and cancels a booking line (+ `UpfrontPaymentFailed`). Booking lines carry `bookingId` in both events (S08 R-08-18).
     */
    public void checkout(String memberId,String session,boolean complete) {
        for(var p:repository.member(memberId)) if(session.equals(p.checkoutSessionId()) && "CHECKOUT_PENDING".equals(p.status())) {
            boolean booking=p.bookingId()!=null;
            var payload=new LinkedHashMap<String,Object>();payload.put("paymentId",p.id());payload.put("memberId",memberId);payload.put("concept",p.concept());payload.put("provider","STRIPE");
            if(booking) payload.put("bookingId",p.bookingId());
            if(complete) {
                repository.update(state(p,"PAID",p.amountDue(),"STRIPE",session));
                payload.put("amountPaid",p.amountDue());emit("UpfrontPaymentSucceeded",p,payload);
            } else if(booking) {
                repository.update(state(p,"CANCELLED",p.amountPaid(),"STRIPE",session));
                emit("UpfrontPaymentFailed",p,payload);
            } else repository.update(state(p,"DUE",p.amountPaid(),null,null));
        }
    }
    private UpfrontPayment state(UpfrontPayment p,String status,Money paid,String provider,String session) {
        return new UpfrontPayment(p.id(),p.clubId(),p.memberId(),p.dogId(),p.concept(),p.signupConcept(),p.amountDue(),paid,status,provider,session,p.createdAt(),
                paid.amountMinor()>0?clock.instant():null,p.bookingId(),p.submissionId(),p.correctionOf());
    }
    private void emit(String type,UpfrontPayment p,Map<String,Object> payload) {
        var user=CurrentUser.current();
        events.publish(new SignupPaymentEvent(type,p.clubId(),p.id(),clock.instant(),payload,user==null?null:user.accountId(),
                user==null||user.impersonation()==null?null:user.impersonation().memberId(),user==null?DomainEvent.Origin.SYSTEM:user.origin()));
    }
}
