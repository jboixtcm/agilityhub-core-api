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
    private final UpfrontPaymentRepository repository;
    private final EventPublisher events;
    private final Clock clock;
    public UpfrontPayments(UpfrontPaymentRepository repository,EventPublisher events,Clock clock) { this.repository=repository;this.events=events;this.clock=clock; }
    public List<Line> lines(String memberId,List<String> dogs) {
        return selected(memberId,dogs).stream().filter(p -> !Set.of("CANCELLED","REFUNDED").contains(p.status())).map(this::line).toList();
    }
    private List<UpfrontPayment> selected(String memberId,List<String> dogs) {
        return repository.member(memberId).stream().filter(p -> dogs==null || dogs.contains(p.dogId())).sorted(Comparator.comparing(UpfrontPayment::createdAt)
                .thenComparing(p -> "ENTRY_FEE".equals(p.concept()) ? 0 : 1).thenComparing(UpfrontPayment::id)).toList();
    }
    private Line line(UpfrontPayment p) { return new Line(p.id(),p.signupConcept()==null?p.concept():p.signupConcept(),p.dogId(),p.amountDue(),p.amountPaid(),p.status(),p.provider()); }
    public void create(String memberId,List<Charge> charges) {
        for (var charge:charges) {
            repository.insert(new UpfrontPayment(UUID.randomUUID().toString(),TenantContext.require(),memberId,charge.dogId(),
                    "ADDITIONAL_DOG_FEE".equals(charge.concept())?"OTHER":charge.concept(),charge.concept(),charge.amount(),new Money(0,charge.amount().currency()),"DUE",null,null,clock.instant(),null));
        }
    }
    public Money due(String memberId,List<String> dogs,String currency) {
        Money amount=new Money(0,currency);
        for (var line:lines(memberId,dogs)) { amount=amount.plus(line.amount().minus(line.paidAmount())); }
        return amount;
    }
    public Money paid(String memberId,List<String> dogs,String currency) {
        Money amount=new Money(0,currency); for (var line:lines(memberId,dogs)) { amount=amount.plus(line.paidAmount()); } return amount;
    }
    public void allocate(String memberId,List<String> dogs,Money amount) {
        if (amount.amountMinor()<0) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        Money due=due(memberId,dogs,amount.currency());
        if (amount.amountMinor()>due.amountMinor()) { throw new ApiException(ErrorCode.UPFRONT_AMOUNT_EXCEEDS_DUE); }
        long remaining=amount.amountMinor();
        for (var p:selected(memberId,dogs)) {
            if (!Set.of("DUE","PARTIAL").contains(p.status()) || remaining==0) { continue; }
            long allocated=Math.min(remaining,p.amountDue().minus(p.amountPaid()).amountMinor()); remaining-=allocated;
            Money paid=p.amountPaid().plus(new Money(allocated,amount.currency()));
            repository.update(state(p,paid.equals(p.amountDue())?"PAID":"PARTIAL",paid,"MANUAL",null));
            emit("UpfrontPaymentRecorded",p,Map.of("paymentId",p.id(),"concept",p.concept(),"provider","MANUAL","amountPaid",new Money(allocated,amount.currency())));
        }
        if (remaining>0) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }
    public void replace(String memberId,List<String> dogs,List<Charge> charges) {
        String currency=charges.isEmpty()?null:charges.getFirst().amount().currency();
        long credit=0;
        for (var p:selected(memberId,dogs)) {
            if (Set.of("CANCELLED","REFUNDED").contains(p.status())) { continue; }
            if (currency!=null) { p.amountDue().plus(new Money(0,currency)); }
            credit+=p.amountPaid().amountMinor();
            if (!"PAID".equals(p.status())) {
                if(p.amountPaid().amountMinor()>0) repository.update(new UpfrontPayment(p.id(),p.clubId(),p.memberId(),p.dogId(),p.concept(),p.signupConcept(),p.amountPaid(),p.amountPaid(),"PAID",p.provider(),p.checkoutSessionId(),p.createdAt(),p.paidAt()));
                else repository.update(state(p,"CANCELLED",p.amountPaid(),p.provider(),p.checkoutSessionId()));
            }
        }
        List<Charge> remaining=new ArrayList<>();
        for (var charge:charges) { long consumed=Math.min(credit,charge.amount().amountMinor()); credit-=consumed;
            if (charge.amount().amountMinor()>consumed) remaining.add(new Charge(charge.concept(),charge.dogId(),new Money(charge.amount().amountMinor()-consumed,charge.amount().currency()))); }
        create(memberId,remaining);
    }
    public boolean reject(String memberId,List<String> dogs) {
        boolean paid=false;
        for(var p:selected(memberId,dogs)) {
            if ("PAID".equals(p.status())) { paid=true; }
            else if (Set.of("DUE","PARTIAL","CHECKOUT_PENDING").contains(p.status())) repository.update(state(p,"CANCELLED",p.amountPaid(),p.provider(),p.checkoutSessionId()));
        }
        return paid;
    }
    public void pending(String memberId,List<String> ids,String session) {
        for(var p:repository.member(memberId)) if(ids.contains(p.id())) {
            if(!Set.of("DUE","PARTIAL").contains(p.status())) throw new ApiException(ErrorCode.INVALID_STATE);
            repository.update(state(p,"CHECKOUT_PENDING",p.amountPaid(),"STRIPE",session));
        }
    }
    public void checkout(String memberId,String session,boolean complete) {
        for(var p:repository.member(memberId)) if(session.equals(p.checkoutSessionId()) && "CHECKOUT_PENDING".equals(p.status())) {
            repository.update(state(p,complete?"PAID":"DUE",complete?p.amountDue():p.amountPaid(),complete?"STRIPE":null,complete?session:null));
            if(complete) emit("UpfrontPaymentSucceeded",p,Map.of("paymentId",p.id(),"memberId",memberId,"amountPaid",p.amountDue(),"provider","STRIPE"));
        }
    }
    private UpfrontPayment state(UpfrontPayment p,String status,Money paid,String provider,String session) {
        return new UpfrontPayment(p.id(),p.clubId(),p.memberId(),p.dogId(),p.concept(),p.signupConcept(),p.amountDue(),paid,status,provider,session,p.createdAt(),paid.amountMinor()>0?clock.instant():null);
    }
    private void emit(String type,UpfrontPayment p,Map<String,Object> payload) {
        var user=CurrentUser.current();
        events.publish(new SignupPaymentEvent(type,p.clubId(),p.id(),clock.instant(),payload,user==null?null:user.accountId(),
                user==null||user.impersonation()==null?null:user.impersonation().memberId(),user==null?DomainEvent.Origin.SYSTEM:user.origin()));
    }
}
