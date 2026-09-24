package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.Money;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class SignupNotifications {
    private final CensusAccess access;private final SignupIdentityService identities;private final SignupLinks links;private final SystemNotificationService notifications;
    private final com.agilityhub.core.platform.application.CensusClubSettings settings;
    public SignupNotifications(CensusAccess access,SignupIdentityService identities,SignupLinks links,SystemNotificationService notifications,
            com.agilityhub.core.platform.application.CensusClubSettings settings) {
        this.access=access;this.identities=identities;this.links=links;this.notifications=notifications;this.settings=settings;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void deliver(String eventId,CensusEvent event) {
        try(var tenant=TenantContext.open(event.clubId())) {
            String id=string(event.payload().get("memberId"));var member=access.members.findById(id).orElse(null);if(member==null||member.erasedAt!=null) return;
            var variables=object("member_name",String.join(" ",Objects.toString(member.firstName,""),Objects.toString(member.lastName1,"")),"member_first_name",member.firstName,"gender",member.gender,"club_name",access.config().club().name());
            String locale=string(map(member.signup).getOrDefault("locale",access.config().club().defaultLocale()));
            variables.put("locale",locale);
            String email=rows(member.contactEmails).isEmpty()?null:string(rows(member.contactEmails).getFirst().get("email"));
            switch(event.type()) {
                case "SignupRecognitionRequested" -> links.send(eventId,member.accountId,false,variables);
                case "MemberValidated" -> { links.send(eventId,member.accountId,true,variables);notifications.appOnce(eventId+":app","N-02",member.accountId,variables); }
                case "SignupSubmitted" -> {
                    // The ids are a collection: `in(Object...)` would search for the list itself and find no dog (`{dogs}` was empty).
                    var dogs=access.dogs.matching(org.springframework.data.mongodb.core.query.Criteria.where("_id").in((Collection<?>)event.payload().get("dogIds")));
                    variables.put("dogs",String.join(", ",dogs.stream().map(d -> d.name).toList()));var plan=access.references.plan(string(map(member.signup).get("planIdRequested")));var names=map(plan.get("name"));if(names.get("values") instanceof Map<?,?>) names=map(names.get("values"));
                    variables.put("plan_name",names.getOrDefault(locale,names.getOrDefault(access.config().club().defaultLocale(),"")));
                    // §8: the applicant's copy tells what to pay and how (the frozen upfront of this submission); it never opens D2.
                    var applicant=new LinkedHashMap<>(variables);String variant=null;
                    var signup=dogs.isEmpty()||dogs.getFirst().signup==null?map(member.signup):map(dogs.getFirst().signup);var total=map(map(signup.get("upfront")).get("totalDue"));
                    if(number(total.get("amountMinor"))>0) {
                        variant="upfront";applicant.put("upfront_total",new Money(number(total.get("amountMinor")),string(total.get("currency"))).format(Locale.forLanguageTag(locale)));
                        // With a provider checkout the payment happens there (pay_link, E8-T04); otherwise the club's MANUAL instructions apply.
                        String instructions=Boolean.TRUE.equals(event.payload().get("checkoutRequired"))?null:settings.manualInstructions(locale);
                        applicant.put("payment_instructions",instructions==null?"":instructions);applicant.put("pay_link","");
                    }
                    notifications.sendApplicantOnce(eventId+":applicant","N-01",variant,email,locale,applicant);
                    if(member.accountId!=null&&"APP_ADD_DOG".equals(event.payload().get("source"))) notifications.appOnce(eventId+":member-app","N-01",member.accountId,applicant);
                    // M9: the admins get their own copy (who applied, which dogs, which plan) with the D2 action.
                    var admins=new LinkedHashMap<>(variables);admins.put("action","OPEN_SIGNUP");admins.put("entityId",id);
                    for(String admin:identities.admins()) { notifications.sendOnceVariant(eventId+":"+admin,"N-01","admin",admin,admins);notifications.appOnce(eventId+":"+admin+":app","N-01",admin,admins); }
                }
                case "SignupRejected" -> { variables.put("reason",event.payload().get("reason"));notifications.sendApplicantOnce(eventId,"N-03",email,locale,variables);if(Boolean.TRUE.equals(event.payload().get("memberWasActive"))) notifications.appOnce(eventId+":app","N-03",member.accountId,variables); }
                case "DogRegistered" -> { var dog=access.dogs.findById(string(event.payload().get("dogId"))).orElse(null);if(dog!=null&&member.accountId!=null) notifications.appOnce(eventId,"N-37",member.accountId,object("dog_name",dog.name,"action","OPEN_DOG","entityId",dog.id)); }
                default -> throw new IllegalArgumentException("Unsupported signup notification");
            }
        }
    }
    @Configuration(proxyBeanMethods=false)
    static class Consumers {
        @Bean DomainEventHandler<CensusEvent> signupSubmittedMail(SignupNotifications service) { return handler("SignupSubmitted",service); }
        @Bean DomainEventHandler<CensusEvent> signupWelcomeMail(SignupNotifications service) { return handler("MemberValidated",service); }
        @Bean DomainEventHandler<CensusEvent> signupRejectedMail(SignupNotifications service) { return handler("SignupRejected",service); }
        @Bean DomainEventHandler<CensusEvent> signupRecognitionMail(SignupNotifications service) { return handler("SignupRecognitionRequested",service); }
        @Bean DomainEventHandler<CensusEvent> signupDogNotification(SignupNotifications service) { return handler("DogRegistered",service); }
        private DomainEventHandler<CensusEvent> handler(String type,SignupNotifications service) {
            return new DomainEventHandler<>() {public String eventType(){return type;}public Class<CensusEvent> eventClass(){return CensusEvent.class;}public void handle(String id,CensusEvent event){service.deliver(id,event);}};
        }
    }
}
