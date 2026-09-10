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
            var due=payments.due(memberId,null,currency);var method=member.get("paymentMethod") instanceof Map<?,?> map?map:Map.of();
            boolean card="CARD".equals(method.get("type"));
            if(due.amountMinor()==0&&!card) throw new ApiException(ErrorCode.INVALID_STATE);
            var lines=payments.lines(memberId,null).stream().filter(l -> l.amount().amountMinor()>l.paidAmount().amountMinor()).toList();
            if(lines.stream().anyMatch(l -> l.status().equals("CHECKOUT_PENDING"))) throw new ApiException(ErrorCode.INVALID_STATE);
            String id=UUID.randomUUID().toString();Instant expires=clock.instant().plus(Duration.ofHours(24));
            var ids=lines.stream().map(UpfrontPayments.Line::id).toList();String mode=due.amountMinor()==0?"setup":"payment";
            var request=new PaymentProvider.Request(id,TenantContext.require(),memberId,mode,lines.stream().map(l -> new PaymentProvider.Item(l.id(),messages.format("signup:payment.concept."+l.concept(),Map.of(),Locale.forLanguageTag((String)member.get("locale"))),l.amount().minus(l.paidAmount()))).toList(),
                    (String)member.get("email"),memberId,Map.of("clubId",TenantContext.require(),"memberId",memberId,"upfrontPaymentIds",ids),card?"off_session":null,success,cancel,expires);
            String url=gateway.createCheckoutSession(request);
            sessions.insert(new SignupCheckoutSession(id,TenantContext.require(),memberId,"PENDING",mode,ids,expires));payments.pending(memberId,ids,id);
            return new Result(url,id);
        });
    }
    private void redirect(String value) {
        try {
            var uri=URI.create(value);
            if(!"https".equals(uri.getScheme())||!clubs.appHost().equalsIgnoreCase(uri.getHost())||uri.getPort()!=-1||uri.getUserInfo()!=null) throw new IllegalArgumentException();
        } catch(IllegalArgumentException|NullPointerException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field","redirectUrl")); }
    }
    public void complete(String sessionId,Map<String,Object> card) { finish(sessionId,true,card); }
    public void expire(String sessionId) { finish(sessionId,false,Map.of()); }
    private void finish(String id,boolean complete,Map<String,Object> card) {
        transactions.run(() -> {
            members.lock();var session=sessions.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if(!"PENDING".equals(session.status())) return null;
            if(complete&&!session.expiresAt().isAfter(clock.instant())) throw new ApiException(ErrorCode.INVALID_STATE);
            if(!sessions.finish(id,complete?"COMPLETE":"EXPIRED")) return null;
            payments.checkout(session.memberId(),id,complete);
            if(complete) members.card(session.memberId(),card);
            return null;
        });
    }
}
