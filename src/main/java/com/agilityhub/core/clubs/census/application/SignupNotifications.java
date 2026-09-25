package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission;
import com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmissionRepository;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.Money;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class SignupNotifications {
    private final CensusAccess access;private final SignupIdentityService identities;private final SignupLinks links;private final SystemNotificationService notifications;
    private final com.agilityhub.core.platform.application.CensusClubSettings settings;
    private final RateLimits limits;private final SignupCapabilities capabilities;
    private final SignupNotificationAdmissionRepository admissions;private final java.time.Clock clock;
    /**
     * The notification whose recipient cap each event type decides (R-04-20). The only place the capped codes are written:
     * {@link #deliver} caps and {@link #processed} retains the decision of the code this map gives (E3-T16).
     */
    private static final Map<String,String> CAPPED=Map.of("SignupRecognitionRequested","N-39","SignupSubmitted","N-01");
    /**
     * E3-T15: one recipient-cap decision at a time. There is one lock per instance, shared by every club, recipient and code
     * (E3-T16, review #5): it is held while the decision is stored (one Mongo insert), which today's single dispatcher thread
     * never contends. The buckets it guards are this instance's too.
     */
    private final java.util.concurrent.locks.ReentrantLock deciding=new java.util.concurrent.locks.ReentrantLock();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SignupNotifications.class);
    public SignupNotifications(CensusAccess access,SignupIdentityService identities,SignupLinks links,SystemNotificationService notifications,
            com.agilityhub.core.platform.application.CensusClubSettings settings,RateLimits limits,SignupCapabilities capabilities,
            SignupNotificationAdmissionRepository admissions,java.time.Clock clock) {
        this.access=access;this.identities=identities;this.links=links;this.notifications=notifications;this.settings=settings;this.limits=limits;this.capabilities=capabilities;
        this.admissions=admissions;this.clock=clock;
    }
    /**
     * R-04-20 (E3-T09): the anonymous routes cannot flood one address. At most the club's
     * `signup.rateLimit.notificationsPerRecipientPerHour` (3 by default) N-39 per account and applicant's N-01 per address,
     * per club; the rest are skipped and logged with a hash, never the address. E3-T12: each event is decided once per
     * notification, so a delivery retried after its admission (a provider failure) still sends; only new events count.
     * Round 2: the decision is persisted when taken ({@link SignupNotificationAdmission}), so it also survives a restart,
     * which empties the per-instance buckets. E3-T15: a stored decision is never charged again, and it has no expiry until
     * its event is processed ({@link #processed}).
     */
    private boolean capped(String eventId,CensusEvent event,String recipient) {
        String clubId=event.clubId(),code=Objects.requireNonNull(CAPPED.get(event.type()),"Uncapped signup notification event");
        String hash=capabilities.fingerprint(recipient.toLowerCase(Locale.ROOT));
        var admission=admissions.decision(eventId,code).orElseGet(() -> decide(clubId,eventId,code,hash));
        if(admission.admitted()) return false;
        LOG.info("Signup notification capped per recipient code={} clubId={} recipientHash={}",code,clubId,hash);
        return true;
    }
    /**
     * E3-T15 step 1: the bucket is probed, the decision stored, and only then does an admission take its token. A failed
     * write throws before the charge, so the retry decides again on an untouched bucket; a decision a concurrent delivery
     * of the same event stored first is used as it is, and charged by that delivery. The lock ({@link #deciding}, one per
     * instance) keeps any other decision, of this recipient or another, from probing in between.
     */
    private SignupNotificationAdmission decide(String clubId,String eventId,String code,String hash) {
        var limit=limits.limit(RateLimits.Route.SIGNUP_RECIPIENT,access.config().get("signup.rateLimit",Map.class));
        String subject=clubId+":"+code+":"+hash;
        deciding.lock();
        try {
            boolean admitted=limits.available(RateLimits.Route.SIGNUP_RECIPIENT,subject,limit);
            var decided=admissions.decide(new SignupNotificationAdmission(SignupNotificationAdmission.id(eventId,code),clubId,eventId,code,admitted,clock.instant(),null));
            if(decided.stored()&&admitted) limits.charge(RateLimits.Route.SIGNUP_RECIPIENT,subject,limit);
            return decided.admission();
        } finally { deciding.unlock(); }
    }
    /**
     * E3-T15 step 2: the consumer calls this after {@link #deliver}, inside the outbox transaction that marks it processed.
     * The decision's retention (its event's, S15 `jobs.retention.domainEventsDays`) starts once that transaction commits:
     * a rolled-back delivery leaves the decision without expiry, however long its event stays pending. The update runs
     * after the commit because the transaction reads a snapshot taken before this delivery stored its decision. If it
     * fails, the decision is kept without expiry (logged): a leftover, never a lost admission.
     */
    @Transactional
    public void processed(String eventId,CensusEvent event) {
        String code=CAPPED.get(event.type());
        if(code==null) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try(var tenant=TenantContext.open(event.clubId())) {
                    int days=access.config().get("jobs.retention.domainEventsDays",Integer.class);
                    admissions.retain(eventId,code,clock.instant().plus(java.time.Duration.ofDays(days)));
                } catch(RuntimeException failure) {
                    LOG.warn("Signup notification admission kept without expiry eventId={} code={} error={}",eventId,code,failure.getClass().getSimpleName());
                }
            }
        });
    }
    /** The event's dogs (`dogIds`). The ids are a collection: `in(Object...)` would search for the list itself and find no dog. */
    private List<com.agilityhub.core.clubs.census.persistence.Dog> dogs(CensusEvent event) {
        var ids=event.payload().get("dogIds") instanceof Collection<?> values?values:List.of();
        return ids.isEmpty()?List.of():access.dogs.matching(org.springframework.data.mongodb.core.query.Criteria.where("_id").in(ids));
    }
    private static List<String> dogNames(Object raw) { return raw instanceof Collection<?> values?values.stream().map(String::valueOf).toList():List.of(); }
    /**
     * The block of the submission these dogs came from (S04 §3): the oldest one when several are decided together, the
     * member's signup for a dog written before the blocks existed.
     */
    private static Map<String,Object> submission(com.agilityhub.core.clubs.census.persistence.Member member,List<com.agilityhub.core.clubs.census.persistence.Dog> dogs) {
        return dogs.stream().filter(d -> d.signup!=null).map(d -> map(d.signup))
                .min(Comparator.comparing((Map<String,Object> b) -> Objects.requireNonNullElse(instant(b.get("submittedAt")),java.time.Instant.MAX))).orElse(map(member.signup));
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void deliver(String eventId,CensusEvent event) {
        try(var tenant=TenantContext.open(event.clubId())) {
            String id=string(event.payload().get("memberId"));var member=access.members.findById(id).orElse(null);if(member==null||member.erasedAt!=null) return;
            // R-04-06 (c): a readmission's N-01 and N-03 go to the applicant its event carries (the submitted primary address,
            // name and `signup.locale`), never to the LEFT record, which a rejection restores before this runs.
            var person=map(event.payload().get("applicant"));boolean submitted=!person.isEmpty();
            String firstName=submitted?string(person.get("firstName")):member.firstName,lastName=submitted?string(person.get("lastName1")):member.lastName1;
            var variables=object("member_name",String.join(" ",Objects.toString(firstName,""),Objects.toString(lastName,"")),"member_first_name",firstName,
                    "gender",submitted?person.get("gender"):member.gender,"club_name",access.config().club().name());
            String locale=string((submitted?person:map(member.signup)).getOrDefault("locale",access.config().club().defaultLocale()));
            variables.put("locale",locale);
            String email=submitted?string(person.get("email")):rows(member.contactEmails).isEmpty()?null:string(rows(member.contactEmails).getFirst().get("email"));
            switch(event.type()) {
                case "SignupRecognitionRequested" -> { if(!capped(eventId,event,member.accountId)) links.send(eventId,member.accountId,false,variables); }
                case "MemberValidated" -> { links.send(eventId,member.accountId,true,variables);notifications.appOnce(eventId+":app","N-02",member.accountId,variables); }
                case "SignupSubmitted" -> {
                    // E3-T10/E3-T12 (R-04-06 c, S04 §8): each copy describes its own submission, even when later ones are queued.
                    // The locale, the dog names, the plan and the upfront total travel in the event: a later submission (a
                    // readmission reusing the dog) rewrites the dogs and their blocks before this runs. An event written
                    // before the payload carried them (no `locale`) reads the submission block, as before.
                    var payload=event.payload();boolean carried=payload.containsKey("locale");
                    var dogs=carried?List.<com.agilityhub.core.clubs.census.persistence.Dog>of():dogs(event);var signup=carried?Map.<String,Object>of():submission(member,dogs);
                    locale=string(carried?payload.get("locale"):signup.getOrDefault("locale",locale));variables.put("locale",locale);
                    variables.put("dogs",String.join(", ",carried?dogNames(payload.get("dogNames")):dogs.stream().map(d -> d.name).toList()));
                    String planId=payload.containsKey("planId")?string(payload.get("planId")):string(signup.get("planIdRequested"));
                    var plan=access.references.plan(planId);var names=map(plan.get("name"));if(names.get("values") instanceof Map<?,?>) names=map(names.get("values"));
                    variables.put("plan_name",names.getOrDefault(locale,names.getOrDefault(access.config().club().defaultLocale(),"")));
                    // §8: the applicant's copy tells what to pay and how (the frozen upfront of this submission); it never opens D2.
                    var applicant=new LinkedHashMap<>(variables);String variant=null;
                    var total=map(carried?payload.get("upfrontTotal"):map(signup.get("upfront")).get("totalDue"));
                    if(number(total.get("amountMinor"))>0) {
                        variant="upfront";applicant.put("upfront_total",new Money(number(total.get("amountMinor")),string(total.get("currency"))).format(Locale.forLanguageTag(locale)));
                        // With a provider checkout the payment happens there (pay_link, E8-T04); otherwise the club's MANUAL instructions apply.
                        String instructions=Boolean.TRUE.equals(event.payload().get("checkoutRequired"))?null:settings.manualInstructions(locale);
                        applicant.put("payment_instructions",instructions==null?"":instructions);applicant.put("pay_link","");
                    }
                    if(email!=null&&!capped(eventId,event,email)) notifications.sendApplicantOnce(eventId+":applicant","N-01",variant,email,locale,applicant);
                    // The member's APP row (add-dog) is the applicant copy too: same variant, total and instructions (E3-T08 round 2).
                    if(member.accountId!=null&&"APP_ADD_DOG".equals(event.payload().get("source"))) notifications.appOnceVariant(eventId+":member-app","N-01",variant,member.accountId,applicant);
                    // M9: the admins get their own copy (who applied, which dogs, which plan) with the D2 action, on both channels.
                    var admins=new LinkedHashMap<>(variables);admins.put("action","OPEN_SIGNUP");admins.put("entityId",id);
                    for(String admin:identities.admins()) { notifications.sendOnceVariant(eventId+":"+admin,"N-01","admin",admin,admins);notifications.appOnceVariant(eventId+":"+admin+":app","N-01","admin",admin,admins); }
                }
                case "SignupRejected" -> {
                    // S04 §8 (E3-T10 round 2, E3-T12): N-03 speaks the language of the rejected submission, which its event
                    // carries (`locale`); an add-dog's is never the public signup's. An older event reads the dogs' blocks.
                    if(event.payload().get("locale")!=null) locale=string(event.payload().get("locale"));
                    else if(!submitted) locale=string(submission(member,dogs(event)).getOrDefault("locale",locale));
                    variables.put("locale",locale);
                    variables.put("reason",event.payload().get("reason"));notifications.sendApplicantOnce(eventId,"N-03",email,locale,variables);if(Boolean.TRUE.equals(event.payload().get("memberWasActive"))) notifications.appOnce(eventId+":app","N-03",member.accountId,variables);
                }
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
            return new DomainEventHandler<>() {public String eventType(){return type;}public Class<CensusEvent> eventClass(){return CensusEvent.class;}public void handle(String id,CensusEvent event){service.deliver(id,event);service.processed(id,event);}};
        }
    }
}
