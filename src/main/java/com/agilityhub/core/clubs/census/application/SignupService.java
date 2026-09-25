package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.clubs.signup.application.SignupPolicy;
import com.agilityhub.core.identity.application.SignupIdentityService;
import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** S04 mutations share the census/catalog locks and the request's Mongo transaction. */
@Service
public class SignupService implements SignupPaymentAccess {
    private final CensusAccess access; private final SignupPolicy policy; private final CensusEvents events;
    private final CensusRepository<DogDocument> documents; private final AttachmentService attachments;
    private final UpfrontPayments payments; private final CensusClubSettings settings; private final SignupIdentityService identities;
    private final SignupCapabilities capabilities; private final CountryContacts countries; private final Clock clock;
    private final CensusQuery queries; private final ObjectMapper mapper; private final IcuMessageSource messages;
    private final com.agilityhub.core.clubs.dashboard.application.DashboardQuery dashboard;
    private final com.agilityhub.core.identity.application.CensusIdentityService accounts; private final AuditWriter audits;
    private static final LocalDate EARLIEST_BIRTH_DATE=LocalDate.of(1900,1,1); // S04 §3 `Member.birthDate`
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<DogService> dogService;
    /**
     * The anonymous `GET /signup` configuration per club and locale, for 60 s (R-04-27). E3-T12: generation-aware
     * ({@link CacheLoads}), so a load that overlapped an eviction (a parameter, plan or club write) never stores what it read.
     */
    private record ConfigKey(String clubId,String locale) { }
    private final CacheLoads<ConfigKey,Map<String,Object>> cache;
    public void invalidateConfiguration(String clubId) { cache.invalidateIf(key -> key.clubId().equals(clubId)); }
    public SignupService(CensusAccess access,SignupPolicy policy,CensusEvents events,CensusRepository<DogDocument> documents,
            AttachmentService attachments,UpfrontPayments payments,CensusClubSettings settings,SignupIdentityService identities,
            SignupCapabilities capabilities,CountryContacts countries,Clock clock,CensusQuery queries,ObjectMapper mapper,IcuMessageSource messages,
            com.agilityhub.core.clubs.dashboard.application.DashboardQuery dashboard,com.agilityhub.core.identity.application.CensusIdentityService accounts,AuditWriter audits) {
        this.access=access;this.policy=policy;this.events=events;this.documents=documents;this.attachments=attachments;this.payments=payments;
        this.settings=settings;this.identities=identities;this.capabilities=capabilities;this.countries=countries;this.clock=clock;this.queries=queries;this.mapper=mapper;this.messages=messages;
        this.dashboard=dashboard;this.accounts=accounts;this.audits=audits;
        this.cache=CacheLoads.of(com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(2000).expireAfterWrite(Duration.ofSeconds(60))
                .ticker(() -> java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(clock.millis())).build());
    }
    /** R-14-01 (M11): the commands that change D1's pending list or KPIs refresh it right after their commit, not only via the outbox. */
    void refreshDashboard() { dashboard.invalidateAfterCommit(TenantContext.require()); }
    private static ApiException notPending() { return new ApiException(ErrorCode.INVALID_STATE,Map.of("reason","NOT_PENDING")); }
    /** S04 §5: the rows of a signup are those of each pending dog's own submission (`submissionId`). */
    private List<UpfrontPayments.Submission> scope(Member member,List<Dog> dogs) {
        return dogs.stream().map(d -> new UpfrontPayments.Submission(d.id,submissionOf(member,d))).toList();
    }
    private String submissionOf(Member member,Dog dog) { return string(block(member,dog).get("submissionId")); }
    /**
     * S04 §3 (E3-T10): the block of the submission that created the dog. A public signup copies `Member.signup`; an add-dog
     * submission lives only here, one block per dog. A dog written before the blocks existed uses the member's signup.
     */
    private Map<String,Object> block(Member member,Dog dog) { return map(dog.signup==null?member.signup:dog.signup); }
    /** The submission D2 describes: the public signup of a PENDING member; for an ACTIVE one, the oldest pending add-dog block. */
    private Map<String,Object> submission(Member member,List<Dog> dogs) {
        if(!"ACTIVE".equals(member.status)) return map(member.signup);
        return dogs.stream().map(d -> block(member,d)).min(Comparator.comparing((Map<String,Object> b) -> Objects.requireNonNullElse(instant(b.get("submittedAt")),Instant.MAX))).orElse(map(member.signup));
    }
    /**
     * The rows a checkout may charge (E3-T10, review of E3-T08 round 2): the rows of every submission the member owes,
     * whatever its dogs' status (a validation with nothing paid, R-04-16, leaves them due) and whatever was submitted after
     * it. Round 2: the scope follows the debtor (`UpfrontPayment.memberId`), not the dogs' current owner, so a dog
     * transferred after an unpaid validation (S03 R-03-14) leaves its rows with the member who owes them. Only the rows
     * that a later public signup superseded (a readmission, R-04-06) are left out: those written before the member's
     * latest public signup ({@link #lastPublicSignup}).
     */
    @Override public List<UpfrontPayments.Submission> submissions(String memberId) {
        var member=access.mutableMember(memberId);var owed=payments.owed(memberId);Instant since=lastPublicSignup(member,blocks(member,owed.keySet()).values());
        return owed.entrySet().stream().filter(e -> since==null||e.getValue().map(at -> !at.isBefore(since)).orElse(true)).map(Map.Entry::getKey).toList();
    }
    /**
     * E3-T13 (review of E3-T10 round 2): the cut of {@link #submissions} is the latest `PUBLIC` block (a public signup or a
     * readmission), never an `APP_ADD_DOG` one. The code before E3-T10 overwrote `Member.signup` with each add-dog's block;
     * on such a record the public signup's own block survives on the dog it created, so the blocks of the owed submissions
     * count too. Null when there is none: nothing is cut.
     */
    private static Instant lastPublicSignup(Member member,Collection<Map<String,Object>> blocks) {
        var candidates=new ArrayList<>(blocks);candidates.add(map(member.signup));
        return candidates.stream().filter(b -> "PUBLIC".equals(b.get("source"))).map(b -> instant(b.get("submittedAt"))).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }
    /**
     * The block of each submission (E3-T13): its dog's own block or `Member.signup`, whichever carries its `submissionId`.
     * The dog is read whoever owns it now (a transfer never moves a debt). A submission no block keeps any more (a row
     * written before submissions existed, a dog whose block a later submission replaced) is absent.
     */
    private Map<UpfrontPayments.Submission,Map<String,Object>> blocks(Member member,Collection<UpfrontPayments.Submission> submissions) {
        var dogs=new HashMap<String,Map<String,Object>>();
        for(var dog:access.dogs.matching(Criteria.where("_id").in(submissions.stream().map(UpfrontPayments.Submission::dogId).distinct().toList()))) dogs.put(dog.id,map(dog.signup));
        var result=new LinkedHashMap<UpfrontPayments.Submission,Map<String,Object>>();
        for(var submission:submissions) {
            if(submission.submissionId()==null) continue;
            java.util.stream.Stream.of(dogs.getOrDefault(submission.dogId(),Map.of()),map(member.signup)).filter(b -> submission.submissionId().equals(b.get("submissionId"))).findFirst()
                    .ifPresent(block -> result.put(submission,block));
        }
        return result;
    }
    /**
     * R-04-26 (E3-T13): each payment line is described in the language of the submission it belongs to: an add-dog's own
     * block, the public signup (for a pending readmission, the applicant's, R-04-06 c). A submission no block describes
     * falls back to `Member.signup`'s language, as before, then to the club's default.
     */
    @Override public Map<UpfrontPayments.Submission,String> locales(String memberId,List<UpfrontPayments.Submission> scope) {
        var member=access.mutableMember(memberId);var blocks=blocks(member,scope);
        String fallback=string(map(member.signup).getOrDefault("locale",access.config().club().defaultLocale()));
        var result=new LinkedHashMap<UpfrontPayments.Submission,String>();
        for(var submission:scope) result.put(submission,Objects.requireNonNullElse(string(map(blocks.get(submission)).get("locale")),fallback));
        return result;
    }
    private static final int USER_AGENT_LENGTH=256;
    /** `Member.signup.userAgent` (§3): the request's, cut to a bounded length. */
    private static String agent(String value) { return value==null||value.isBlank()?null:value.length()>USER_AGENT_LENGTH?value.substring(0,USER_AGENT_LENGTH):value; }
    /** The decision stamped on a submission block (§3): `validatedAt/By`, or `rejectedAt/By` with the reason. */
    private static Map<String,Object> stamped(Map<String,Object> block,Map<String,Object> decision) { var result=new LinkedHashMap<>(block);result.putAll(decision);return result; }
    public void lock() { access.members.lock();policy.lock(); }
    private boolean billing() { return access.enabled(Module.BILLING); }
    private String currency() { return access.config().club().currency(); }
    private String locale() { return LocaleContext.current().getLanguage(); }
    private String legalVersion() { return string(settings.signupLegal(locale()).get("legalTextsVersion")); }
    private String fullName(Member m) { return String.join(" ",m.firstName,m.lastName1,m.lastName2==null?"":m.lastName2).strip(); }
    private String email(Member m) { return string(rows(m.contactEmails).getFirst().get("email")); }
    /**
     * The checkout's view of the member (R-04-26). E3-T12: while a readmission waits, the provider's customer is the
     * applicant's submitted primary address (R-04-06 c), never the LEFT record's; the record and the account's login
     * email are untouched. The lines' language is each submission's ({@link #locales}, E3-T13), not the member's.
     */
    public Map<String,Object> member(String memberId) {
        var m=access.mutableMember(memberId);Object email=readmissionPending(m)?primaryEmail(submitted(m).get("contactEmails")):email(m);
        return object("id",m.id,"email",email,"paymentMethod",effectivePayment(m),"status",m.status);
    }
    public void authorize(String id,String token) {
        if(CurrentUser.current()==null) { capabilities.require(id,token); }
        else if(!access.role("ADMIN") && !access.me().id.equals(id)) { throw new ApiException(ErrorCode.FORBIDDEN); }
        access.mutableMember(id);
    }
    public void card(String id,Map<String,Object> card) {
        var member=access.mutableMember(id);
        if(readmissionPending(member)) {
            // E38: the card of a readmission belongs to the submitted method until validation applies it.
            if("CARD".equals(map(submitted(member).get("paymentMethod")).get("type"))) { putSubmitted(member,"paymentMethod",object("type","CARD","card",card));access.members.save(member); }
            return;
        }
        if("CARD".equals(map(member.paymentMethod).get("type"))) { member.paymentMethod=object("type","CARD","card",card);access.members.save(member); }
    }
    /**
     * R-04-27 (E3-T09): `POST /signup` and the anonymous routes decide `SIGNUP_CLOSED` on the committed club and parameters,
     * never on a cached configuration.
     */
    public void requireOpen() {
        var config=access.currentConfig();
        if(!"ACTIVE".equals(config.club().status()) || !Boolean.TRUE.equals(config.get("signup.enabled",Boolean.class))) { throw new ApiException(ErrorCode.SIGNUP_CLOSED); }
    }
    private List<Dog> dogs(String memberId) { return access.dogs.matching(Criteria.where("memberId").is(memberId)); }
    private List<Dog> pending(String memberId) { return dogs(memberId).stream().filter(d -> "PENDING".equals(d.status)).toList(); }
    private List<String> dogIds(List<Dog> dogs) { return dogs.stream().map(d -> d.id).toList(); }
    /**
     * R-04-05/R-04-06 matching through the `member_id_document`, `contactEmails.email` and
     * `readmissionRequest.submitted.contactEmails.email` indexes (E3-T09: the anonymous lookups never scan the club's
     * members). The `$type` clause lets Mongo use the partial `member_id_document` index. A pending readmission is also
     * matched on the primary address it submitted (R-04-06 a), as any pending member on its own.
     */
    private Member match(String document,List<Map<String,Object>> emails,boolean left) {
        var statuses=left?List.of("LEFT"):List.of("PENDING","ACTIVE");
        var exact=access.members.matching(new Criteria().andOperator(Criteria.where("idDocument.number").is(document),Criteria.where("idDocument.number").type(org.springframework.data.mongodb.core.schema.JsonSchemaObject.Type.STRING),
                Criteria.where("status").in(statuses))).stream().findFirst().orElse(null);
        if(exact!=null || left) return exact;
        var addresses=emails.stream().map(e -> e.get("email")).toList();
        var primary=access.members.matching(Criteria.where("contactEmails.email").in(addresses).and("status").in(statuses)).stream()
                .filter(m -> addresses.contains(primaryEmail(m.contactEmails))).findFirst();
        if(primary.isPresent()) return primary.get();
        return access.members.matching(Criteria.where("readmissionRequest.submitted.contactEmails.email").in(addresses).and("status").is("PENDING")).stream()
                .filter(m -> readmissionPending(m) && addresses.contains(primaryEmail(submitted(m).get("contactEmails")))).findFirst().orElse(null);
    }
    private static Object primaryEmail(Object contactEmails) { var rows=rows(contactEmails);return rows.isEmpty()?null:rows.getFirst().get("email"); }
    @Transactional
    public Map<String,Object> identityCheck(Map<String,Object> request) {
        requireOpen();
        var id=map(request.get("idDocument"));String number=policy.document(string(id.get("type")),string(id.get("value")));
        var emails=policy.emails(strings(request.get("emails")));var m=match(number,emails,false);
        if(m==null) return object("result","NEW");
        if("PENDING".equals(m.status)) return object("result","SIGNUP_ALREADY_PENDING");
        if(m.accountId==null) return object("result","CONTACT_CLUB");
        // R-04-05 (E3-T09): the link of N-39 goes to the account's access address, so that is the one masked.
        String address;
        try { address=accounts.accessEmail(m.accountId); } catch(ApiException inactive) { return object("result","CONTACT_CLUB"); }
        events.emit("SignupRecognitionRequested","Member",m.id,object("memberId",m.id,"accountId",m.accountId,"redirect","/gossos/nou"));
        return object("result","VERIFICATION_SENT","maskedEmail",policy.maskedEmail(address));
    }
    @SuppressWarnings("unchecked") private List<String> strings(Object raw) { return (List<String>)raw; }
    /**
     * R-04-12: the candidates are the owners of an active or pending dog with that name, then {@link SignupPolicy#family}
     * decides exactly as before. E3-T09: no scan of the club's members; the dogs are found through their normalised
     * `nameKey` (trimmed, inner spaces collapsed; round 2), compared case- and accent-insensitively by its index.
     */
    private Optional<SignupPolicy.Match> holder(String holder,String dog) {
        String name=Objects.requireNonNullElse(Dog.nameKey(dog),"");
        var owned=new LinkedHashMap<String,List<Dog>>();
        for(var d:access.dogs.matchingIgnoringCase(Criteria.where("nameKey").is(name).and("status").in("PENDING","ACTIVE"))) owned.computeIfAbsent(d.memberId,k -> new ArrayList<>()).add(d);
        if(owned.isEmpty()) return Optional.empty();
        var candidates=access.members.matching(Criteria.where("_id").in(owned.keySet()).and("status").in("PENDING","ACTIVE")).stream().map(m -> new SignupPolicy.Candidate(m.clubId,m.id,m.status,m.firstName,m.lastName1,m.lastName2,
                owned.get(m.id).stream().map(d -> new SignupPolicy.NamedDog(d.name,d.status)).toList())).toList();
        return policy.family(holder,dog,candidates);
    }
    public Map<String,Object> familyLookup(String name,String dog) {
        requireOpen();access.require(Module.FAMILY_GROUP);
        return holder(name,dog).map(m -> object("result","FOUND","holderDisplayName",m.holderDisplayName())).orElse(object("result","NOT_FOUND"));
    }
    private Map<String,Object> claim(Map<String,Object> raw) {
        if(raw.isEmpty()) return object("status","NONE");
        if(!access.enabled(Module.FAMILY_GROUP)) throw invalid("familyGroupClaim","MODULE_DISABLED");
        String name=text(raw.get("holderName"),"familyGroupClaim.holderName",120,true),dog=text(raw.get("dogName"),"familyGroupClaim.dogName",40,true);
        var match=holder(name,dog);
        if(match.isPresent()) return object("status","FOUND","holderName",name,"dogName",dog,"holderMemberId",match.get().holderMemberId());
        boolean allowed=Boolean.TRUE.equals(access.config().get("signup.allowFamilyGroupPending",Boolean.class));
        if(Boolean.TRUE.equals(raw.get("leavePending"))) { if(!allowed) throw invalid("familyGroupClaim.leavePending","NOT_ALLOWED"); }
        // R-04-12 (E3-T10): the lookup found the holder, but at submission the match is no longer unique or eligible. The
        // claim waits for D2 as NOT_FOUND_PENDING when the club allows it; otherwise the applicant must fix step 18.
        else if(!allowed) throw new ApiException(ErrorCode.FAMILY_HOLDER_NOT_FOUND);
        return object("status","NOT_FOUND_PENDING","holderName",name,"dogName",dog);
    }
    /** The D2 edit of `signup.planIdRequested` is an admin decision: any assignable plan (M8). */
    public void requirePlan(String planId) { policy.requireAssignable(planId); }
    public List<SignupPolicy.Consent> history(Member member) {
        return ConsentLedgers.entries(member.consents).stream().map(r -> new SignupPolicy.Consent(string(r.get("type")),Boolean.TRUE.equals(r.get("granted")),string(r.get("version")),instant(r.get("acceptedAt")),string(r.get("locale")),string(r.get("ipHash")),string(r.get("source")))).toList();
    }
    /**
     * R-04-17: the ledger entries of this acceptance. Each records who accepted it (E3-T09): the applicant (`origin =
     * PUBLIC`, no account), the member (`APP`, their account) or, under impersonation, the actor (`BACKOFFICE`,
     * `actorAccountId` = the admin), never the member's own acceptance.
     */
    private List<Map<String,Object>> consentRows(Member member,Map<String,Object> raw,boolean addDog,String locale,String ip) {
        var privacy=map(raw.get("privacyPolicy"));var image=map(raw.get("imageUse"));
        String version=legalVersion();
        if(!raw.isEmpty() && !version.equals(image.get("version"))) throw new ApiException(ErrorCode.CONSENT_VERSION_OUTDATED);
        var entries=policy.consent((Boolean)privacy.get("accepted"),string(privacy.get("version")),(Boolean)image.get("granted"),version,addDog,history(member),locale,capabilities.fingerprint(ip));
        var user=CurrentUser.current();
        String origin=user==null?"PUBLIC":user.origin().name();
        String actor=user==null?null:user.impersonation()!=null?user.impersonation().actorAccountId():user.accountId();
        return entries.stream().map(entry -> object("type",entry.type(),"granted",entry.granted(),"version",entry.version(),"acceptedAt",entry.acceptedAt(),"locale",entry.locale(),"ipHash",entry.ipHash(),"source",entry.source(),
                "origin",origin,"actorAccountId",actor)).toList();
    }
    private void appendConsents(Member member,List<Map<String,Object>> rows) {
        if(rows.isEmpty()) return;
        var ledger=new ArrayList<>(ConsentLedgers.entries(member.consents));ledger.addAll(rows);
        member.consents=new ConsentLedgerConverter().read(ledger,null);
    }
    // ---- R-04-06 (E38): a readmission waits in `readmissionRequest` and never overwrites the LEFT record before validation ----
    public boolean readmissionPending(Member member) { return member.readmissionRequest!=null&&"PENDING".equals(member.status); }
    private Map<String,Object> submitted(Member member) { return map(map(member.readmissionRequest).get("submitted")); }
    private void putSubmitted(Member member,String key,Object value) {
        var request=new LinkedHashMap<>(member.readmissionRequest);var values=new LinkedHashMap<>(submitted(member));
        if(value==null) values.remove(key);else values.put(key,value);
        request.put("submitted",values);member.readmissionRequest=request;
    }
    /** The payment method the signup runs with: the submitted one while a readmission waits, the record's otherwise. */
    private Map<String,Object> effectivePayment(Member member) { return readmissionPending(member)?map(submitted(member).get("paymentMethod")):member.paymentMethod; }
    static final List<String> READMISSION_FIELDS=List.of("firstName","lastName1","lastName2","gender","birthDate","contactEmails","phones","address","paymentMethod");
    /** A copy of the member holding the submitted values, for a D2 edit of a pending readmission ({@link MemberService}). */
    public Member submittedView(Member member) {
        var values=submitted(member);var view=new Member();view.id=member.id;view.clubId=member.clubId;
        view.firstName=string(values.get("firstName"));view.lastName1=string(values.get("lastName1"));view.lastName2=string(values.get("lastName2"));view.gender=string(values.get("gender"));
        view.birthDate=values.get("birthDate")==null?null:LocalDate.parse(string(values.get("birthDate")));
        view.contactEmails=rows(values.get("contactEmails"));view.phones=rows(values.get("phones"));view.address=map(values.get("address"));
        view.paymentMethod=values.get("paymentMethod")==null?null:map(values.get("paymentMethod"));
        return view;
    }
    /** Stores the (edited) submitted values of {@link #submittedView} back into the request; the record is untouched. */
    public void storeSubmitted(Member member,Member view) {
        putSubmitted(member,"firstName",view.firstName);putSubmitted(member,"lastName1",view.lastName1);putSubmitted(member,"lastName2",view.lastName2);putSubmitted(member,"gender",view.gender);
        putSubmitted(member,"birthDate",view.birthDate==null?null:view.birthDate.toString());putSubmitted(member,"contactEmails",view.contactEmails);putSubmitted(member,"phones",view.phones);
        putSubmitted(member,"address",view.address);putSubmitted(member,"paymentMethod",view.paymentMethod);
    }
    /** Validation applies the submitted values; the MEMBER_VALIDATED audit records them as a masked diff (R-14-09). */
    private void applyReadmission(Member member) {
        var view=submittedView(member);
        member.firstName=view.firstName;member.lastName1=view.lastName1;member.lastName2=view.lastName2;member.gender=view.gender;
        if(view.birthDate!=null) member.birthDate=view.birthDate;
        member.contactEmails=view.contactEmails;member.phones=view.phones;member.address=view.address;
        if(view.paymentMethod!=null) member.paymentMethod=view.paymentMethod;
        appendConsents(member,rows(submitted(member).get("consents")));
        member.readmissionRequest=null;
    }
    /**
     * R-04-06 (c): the recipient of a readmission's N-01 and N-03 (S04 §8 `APPLICANT`): the submitted primary address and
     * name, in `signup.locale`. It travels in the event because a rejection drops the submitted values before delivery.
     */
    private Map<String,Object> applicant(Member member) {
        var person=submitted(member);
        return object("email",primaryEmail(person.get("contactEmails")),"firstName",person.get("firstName"),"lastName1",person.get("lastName1"),"gender",person.get("gender"),
                "locale",map(member.signup).get("locale"));
    }
    /** R-04-23 (E38): a rejected readmission leaves the LEFT record exactly as it was before the submission. */
    private void restoreLeft(Member member) {
        var previous=map(map(member.readmissionRequest).get("previous"));
        member.status=string(previous.getOrDefault("status","LEFT"));member.leftAt=instant(previous.get("leftAt"));member.leftReason=string(previous.get("leftReason"));
        member.leaveDate=previous.get("leaveDate")==null?null:LocalDate.parse(string(previous.get("leaveDate")));
        member.familyGroupClaim=previous.get("familyGroupClaim")==null?null:new LinkedHashMap<>(map(previous.get("familyGroupClaim")));
        member.signup=previous.get("signup")==null?null:new LinkedHashMap<>(map(previous.get("signup")));
        member.readmissionRequest=null;
    }
    public Map<String,Object> payment(Map<String,Object> raw,Member member,String defaultHolder) {
        if(!billing()) { if(raw.get("iban")!=null) throw invalid("payment.iban","MODULE_DISABLED");return null; }
        String type=string(raw.get("type"));
        String provider=PaymentProviderFlags.provider(type);
        if(provider==null||!settings.providerEnabled(provider)) throw new ApiException(ErrorCode.PAYMENT_METHOD_NOT_AVAILABLE);
        var result=object("type",type);
        if(type.equals("SEPA_DD")) {
            String iban=string(raw.get("iban"));if(iban!=null) iban=iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
            if(iban!=null && !iban.isBlank() && !countries.iban(iban)) throw new ApiException(ErrorCode.INVALID_IBAN);
            String tax=text(raw.get("holderTaxId"),"payment.holderTaxId",80,false);
            if(tax!=null && !tax.isBlank() && "ES".equals(countries.countryCode())) countries.normalizeDocument(tax.matches("(?i)[XYZ].*")?"NIE":"DNI",tax);
            result.putAll(object("iban",iban==null||iban.isBlank()?null:iban,"holderName",raw.get("holderName")==null?defaultHolder:text(raw.get("holderName"),"payment.holderName",120,true),"holderTaxId",tax,"mandateSignedAt",clock.instant()));
        }
        return result;
    }
    @Transactional
    public Map<String,Object> submit(Map<String,Object> request,String ip,String userAgent) {
        requireOpen();lock();var person=map(request.get("person"));var id=map(person.get("idDocument"));
        String number=policy.document(string(id.get("type")),string(id.get("value")));var emails=policy.emails(strings(person.get("emails")));
        var existing=match(number,emails,false);
        if(existing!=null) throw new ApiException("PENDING".equals(existing.status)?ErrorCode.SIGNUP_ALREADY_PENDING:ErrorCode.MEMBER_ALREADY_EXISTS);
        var member=match(number,emails,true);boolean readmission=member!=null;
        if(member==null) { member=new Member();member.id=UUID.randomUUID().toString();member.clubId=TenantContext.require(); }
        if(member.erasedAt!=null) throw new ApiException(ErrorCode.MEMBER_ERASED);
        var before=readmission?CensusAudit.view(member,mapper):null;
        String locale=string(request.get("locale"));if(!access.config().club().locales().contains(locale)) throw invalid("locale","INVALID_VALUE");
        // R-04-06 (E38): a readmission validates the submitted person the same way, but keeps it apart from the LEFT record.
        var target=readmission?new Member():member;
        if(!readmission) member.idDocument=object("type",id.get("type"),"number",number);
        target.firstName=text(person.get("firstName"),"firstName",60,true);target.lastName1=text(person.get("lastName1"),"lastName1",60,true);target.lastName2=text(person.get("lastName2"),"lastName2",60,false);
        target.gender=string(person.get("gender"));target.birthDate=date(person.get("birthDate"));
        if(!target.birthDate.isBefore(policy.today())||target.birthDate.isBefore(EARLIEST_BIRTH_DATE)) throw invalid("birthDate","INVALID_VALUE");
        target.contactEmails=emails;target.phones=policy.phones(rows(person.get("phones")));
        var address=map(person.get("address"));target.address=object("street",address.get("street"),"postalCode",address.get("postalCode"),"city",policy.town(string(address.get("postalCode")),string(address.get("town"))),"country",countries.countryCode());
        var previousClaim=member.familyGroupClaim;
        member.familyGroupClaim=claim(map(request.get("familyGroupClaim")));
        String holderId=string(member.familyGroupClaim.get("holderMemberId"));
        target.paymentMethod=payment(map(request.get("payment")),member,holderId==null?fullName(target):fullName(access.members.require(holderId)));
        var consents=consentRows(member,map(request.get("consents")),false,locale,ip);
        if(readmission) {
            member.readmissionRequest=object("submitted",object("firstName",target.firstName,"lastName1",target.lastName1,"lastName2",target.lastName2,"gender",target.gender,
                            "birthDate",target.birthDate.toString(),"contactEmails",target.contactEmails,"phones",target.phones,"address",target.address,"paymentMethod",target.paymentMethod,"consents",consents),
                    "previous",object("status",member.status,"leftAt",member.leftAt,"leftReason",member.leftReason,"leaveDate",member.leaveDate==null?null:member.leaveDate.toString(),
                            "familyGroupClaim",previousClaim,"signup",member.signup));
        } else appendConsents(member,consents);
        String planId=string(request.get("planId"));policy.require(planId);
        String option=string(map(request.get("payment")).getOrDefault("firstMonthOption","TODAY"));String submission=UUID.randomUUID().toString();
        // S04 §3 (E3-T10): the snapshot of the submission, with its origin (hashed IP, user agent) and the requested method.
        member.status="PENDING";member.signup=object("submittedAt",clock.instant(),"locale",locale,"source","PUBLIC","readmission",readmission,"planIdRequested",planId,
                "paymentMethodTypeRequested",target.paymentMethod==null?null:target.paymentMethod.get("type"),"firstMonthOption",option,"submissionId",submission,
                "ipHash",capabilities.fingerprint(ip),"userAgent",agent(userAgent));
        var prepared=prepareDog(member,map(request.get("dog")),readmission);
        var quote=policy.quote(planId,prepared.dog().id,false,null,option,policy.today());freeze(member.signup,quote);
        if(readmission) access.members.save(member);else access.members.insert(member);
        var dog=storeDog(prepared,member.signup,rows(map(request.get("dog")).get("documents")));createPayments(member.id,submission,quote);
        boolean checkout=checkoutRequired(member,quote.totalDue());
        events.emit("SignupSubmitted","Member",member.id,object("memberId",member.id,"dogIds",List.of(dog.id),"planId",planId,"paymentMethodType",map(effectivePayment(member)).get("type"),"source","PUBLIC","readmission",readmission,"checkoutRequired",checkout,
                "applicant",readmission?applicant(member):null,"locale",locale,"dogNames",List.of(dog.name),"upfrontTotal",upfrontTotal(quote)));
        // R-04-06 (E38): the readmission submission is audited with the masked diff; anonymous, so its origin is PUBLIC.
        if(readmission) audits.write(new AuditCommand(AuditAction.SIGNUP_SUBMITTED,"Member",member.id,member.id,before,CensusAudit.view(member,mapper),null),CurrentUser.current()==null?"PUBLIC":null);
        refreshDashboard();
        return object("memberId",member.id,"signupToken",capabilities.issue(member.id),"upfront",billing()?upfront(member,List.of(dog),quote):null,"checkout",object("required",checkout));
    }
    /**
     * E3-T12 (R-04-06 c, S04 §8): N-01 describes its own submission, so its event carries what a later submission could
     * rewrite before delivery: `locale`, `dogNames` and `upfrontTotal` (the frozen `signup.upfront.totalDue`; null without BILLING).
     */
    private Map<String,Object> upfrontTotal(SignupPolicy.Quote quote) { return billing()?money(quote.totalDue()):null; }
    private boolean checkoutRequired(Member m,Money due) { return billing()&&settings.providerEnabled("STRIPE")&&(due.amountMinor()>0 || "CARD".equals(map(effectivePayment(m)).get("type"))); }
    private static List<UpfrontPayments.Charge> charges(SignupPolicy.Quote quote) { return quote.lines().stream().map(l -> new UpfrontPayments.Charge(l.concept(),l.dogId(),l.amountDue())).toList(); }
    private void createPayments(String member,String submission,SignupPolicy.Quote quote) { payments.create(member,submission,charges(quote)); }
    private static Map<String,Object> money(Money value) { return object("amountMinor",value.amountMinor(),"currency",value.currency()); }
    private static Map<String,Object> period(SignupPolicy.Period value) { return value==null?null:object("option",value.option(),"portion",value.portion(),"startDate",value.startDate().toString(),"amountDue",money(value.amountDue())); }
    /** `signup.upfront` (§3): the quote frozen in the submission block; N-01 reads its `totalDue`, D2 its first month. Only with BILLING. */
    private void freeze(Map<String,Object> block,SignupPolicy.Quote quote) {
        if(!billing()) return;
        block.put("upfront",object("lines",quote.lines().stream().map(l -> object("concept",l.concept(),"dogId",l.dogId(),"amountDue",money(l.amountDue()))).toList(),
                "firstMonth",period(quote.firstMonth()),"additionalDog",period(quote.additionalDog()),"totalDue",money(quote.totalDue())));
    }
    private Map<String,Object> upfront(Member member,List<Dog> dogs,SignupPolicy.Quote quote) {
        var scope=scope(member,dogs);
        return object("lines",paymentLines(member.id,scope),"totalDue",payments.due(member.id,scope,currency()),"additionalDog",quote==null?null:quote.additionalDog());
    }
    public List<Map<String,Object>> paymentLines(String id,List<UpfrontPayments.Submission> scope) { return lineViews(payments.lines(id,scope)); }
    private static List<Map<String,Object>> lineViews(List<UpfrontPayments.Line> lines) {
        return lines.stream().map(l -> object("id",l.id(),"concept",l.concept(),"amount",l.amount(),"paidAmount",l.paidAmount(),"status",l.status(),"provider",l.provider())).toList();
    }
    private record PreparedDog(Dog dog,boolean fresh) { }
    /** Validates and resolves the submitted dog without writing it, so the quote can be frozen before the member is stored. */
    private PreparedDog prepareDog(Member member,Map<String,Object> raw,boolean readmission) {
        // M21: the chip is normalised and checked per country profile before the uniqueness check and the readmission match.
        String chip=policy.chip(text(raw.get("chip"),"dog.chip",40,true),"dog.chip");var matches=access.dogs.matching(Criteria.where("chip").is(chip));Dog dog=null;
        if(!matches.isEmpty()) { dog=matches.getFirst();if(!readmission || !member.id.equals(dog.memberId) || !"INACTIVE".equals(dog.status)) throw new ApiException(ErrorCode.DOG_CHIP_ALREADY_REGISTERED); }
        boolean fresh=dog==null;if(fresh) { dog=new Dog();dog.id=UUID.randomUUID().toString();dog.clubId=TenantContext.require();dog.memberId=member.id; }
        dog.name=text(raw.get("name"),"dog.name",40,true);dog.sex=string(raw.get("sex"));dog.breed=text(raw.get("breed"),"dog.breed",60,true);dog.birthDate=YearMonth.parse(string(raw.get("birthMonth"))).atDay(1);
        if(dog.birthDate.isAfter(policy.today())) throw invalid("dog.birthMonth","INVALID_VALUE");
        dog.chip=chip;dog.status="PENDING";dog.deactivatedAt=null;dog.deactivationReason=null;
        dog.instructorNote=object("text",text(raw.get("notesToInstructors"),"dog.notesToInstructors",1000,false),"updatedAt",clock.instant());
        return new PreparedDog(dog,fresh);
    }
    private Dog storeDog(PreparedDog prepared,Map<String,Object> block,List<Map<String,Object>> files) {
        var dog=prepared.dog();dog.signup=new LinkedHashMap<>(block);
        if(prepared.fresh()) access.dogs.insert(dog);else access.dogs.save(dog);saveDocuments(dog,files);return dog;
    }
    /** The normalised chip of a D2 dog PATCH (§3 `Dog.chip`, M21). */
    public String chip(String raw,String field) { return policy.chip(raw,field); }
    public void saveDocuments(Dog dog,List<Map<String,Object>> submitted) {
        if(submitted.stream().mapToInt(d -> rows(d.get("files")).size()).sum()>10) throw invalid("documents","TOO_MANY_FILES");
        var types=new HashSet<String>();var all=new ArrayList<>(submitted);
        if(all.stream().noneMatch(d -> "VACCINATION_CARD".equals(d.get("type")))) all.add(object("type","VACCINATION_CARD","files",List.of()));
        for(var raw:all) {
            String type=text(raw.get("type"),"documents.type",80,true);if(!types.add(type)) throw invalid("documents","DUPLICATE");
            if(rows(access.config().get("census.dogDocumentTypes",List.class)).stream().noneMatch(t -> type.equals(t.get("key")))) throw new ApiException(ErrorCode.DOCUMENT_TYPE_UNKNOWN);
            var files=new ArrayList<Map<String,Object>>();for(var file:rows(raw.get("files"))) {
                String name=text(file.get("name"),"documents.files.name",80,true);var claimed=attachments.claimSignup(string(file.get("fileKey")),dog.id+":"+type);
                files.add(object("id",claimed.id(),"fileKey",claimed.fileKey(),"name",name,"mimeType",claimed.mimeType(),"sizeBytes",claimed.sizeBytes(),"uploadedAt",claimed.uploadedAt()));
            }
            if(type.equals("VACCINATION_CARD")&&files.isEmpty()&&Boolean.TRUE.equals(access.config().get("signup.requireDogDocumentAtSignup",Boolean.class))) throw new ApiException(ErrorCode.DOG_DOCUMENT_REQUIRED);
            var doc=documents.matching(Criteria.where("dogId").is(dog.id).and("type").is(type)).stream().findFirst().orElse(null);
            boolean fresh=doc==null;if(fresh) { doc=new DogDocument();doc.id=UUID.randomUUID().toString();doc.clubId=TenantContext.require();doc.dogId=dog.id;doc.type=type; }
            boolean received="RECEIVED".equals(doc.state);
            doc.files=files;doc.state=files.isEmpty()?"PENDING":"RECEIVED";if(fresh) documents.insert(doc);else documents.save(doc);
            // S03 §7 (E3-T10): `trigger` REGISTRATION for a document missing at signup, FILE_REMOVED when a D2 edit empties a received one.
            if(files.isEmpty()) events.emit("DogDocumentPending","DogDocument",doc.id,object("dogId",dog.id,"type",type,"state","PENDING","trigger",received?"FILE_REMOVED":"REGISTRATION"));
        }
    }
    @Transactional
    @Audited(action=AuditAction.SIGNUP_EDITED,entityType="'Dog'",entity="#result['dogId']",member="#memberId")
    public Map<String,Object> addDog(String memberId,Map<String,Object> request,String ip,String userAgent) {
        lock();var member=access.me();if(!"ACTIVE".equals(member.status)) throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE);
        if(member.erasedAt!=null) throw new ApiException(ErrorCode.MEMBER_ERASED);
        String requested=string(request.get("planIdRequested"));String planId=requested==null?member.planId:requested;
        // M8: the member's own plan is assignable even when it is hidden from the public offer (the family fare); another plan must be offered.
        if(Objects.equals(planId,member.planId)) policy.requireAssignable(planId);else policy.require(planId);
        appendConsents(member,consentRows(member,map(request.get("consents")),true,locale(),ip));
        String option=string(request.getOrDefault("additionalDogOption","TODAY"));String submission=UUID.randomUUID().toString();
        // S04 §3 (E3-T10): an add-dog submission never overwrites the member's public signup; its block lives on its dog only.
        var block=object("submittedAt",clock.instant(),"locale",locale(),"source","APP_ADD_DOG","readmission",false,"planIdRequested",planId,
                "paymentMethodTypeRequested",billing()?map(member.paymentMethod).get("type"):null,"additionalDogOption",option,"submissionId",submission,
                "ipHash",capabilities.fingerprint(ip),"userAgent",agent(userAgent));
        var prepared=prepareDog(member,map(request.get("dog")),false);
        var quote=policy.quote(planId,prepared.dog().id,true,member.planId,option,policy.today());freeze(block,quote);
        access.members.save(member);var dog=storeDog(prepared,block,rows(request.get("documents")));createPayments(member.id,submission,quote);
        boolean checkout=checkoutRequired(member,quote.totalDue());
        events.emit("SignupSubmitted","Member",member.id,object("memberId",member.id,"dogIds",List.of(dog.id),"planId",planId,"paymentMethodType",map(member.paymentMethod).get("type"),"source","APP_ADD_DOG","readmission",false,"checkoutRequired",checkout,
                "locale",locale(),"dogNames",List.of(dog.name),"upfrontTotal",upfrontTotal(quote)));
        refreshDashboard();
        return object("dogId",dog.id,"memberId",member.id,"upfront",billing()?upfront(member,List.of(dog),quote):null,"checkout",object("required",checkout,"memberId",member.id));
    }
    public Map<String,Object> configuration() {
        if(CurrentUser.current()!=null) return configurationFor(access.me());
        return cache.get(new ConfigKey(TenantContext.require(),locale()),key -> configurationFor(null));
    }
    private Map<String,Object> configurationFor(Member me) {
        var config=access.config();String language=locale();
        if(!"ACTIVE".equals(config.club().status()) || !Boolean.TRUE.equals(config.get("signup.enabled",Boolean.class))) return object("enabled",false,"closedText",textParameter("signup.text.closed"));
        var plans=policy.plans();
        var legal=new LinkedHashMap<>(settings.signupLegal(language));legal.remove("legalName");
        var result=object("enabled",true,"steps",access.enabled(Module.FAMILY_GROUP)&&me==null?List.of("PERSON","DOG","FAMILY_GROUP","PAYMENT"):List.of("PERSON","DOG","PAYMENT"),
                "plans",plans.stream().map(this::planView).toList(),"texts",texts(language),"legal",legal,"countryProfile",countries.signupProfile(),
                // E3-T08 step 12: the web enforces what the server enforces at submission.
                "requireDogDocumentAtSignup",Boolean.TRUE.equals(config.get("signup.requireDogDocumentAtSignup",Boolean.class)));
        if(access.enabled(Module.FAMILY_GROUP)) result.put("allowFamilyGroupPending",Boolean.TRUE.equals(config.get("signup.allowFamilyGroupPending",Boolean.class)));
        if(billing()) {
            // M7 (§2 row 19): the MANUAL method carries the club's own payment instructions; the cash conditions stay in `texts`.
            result.put("paymentMethods",offeredMethods().stream().map(type -> object("type",type,"label",paymentLabel(type,language),
                    "instructions",type.equals("MANUAL")?settings.manualInstructions(language):null,"mandateText",type.equals("SEPA_DD")?mandate(language):null)).toList());
            var monthly=plans.stream().filter(plan -> "MONTHLY_FEE".equals(plan.billingMode())&&plan.price()!=null).findFirst().orElse(null);
            var upfront=object("firstMonthSplitDay",config.get("signup.firstMonthSplitDay",Integer.class),"today",policy.today(),"firstMonthOptions",monthly==null?List.of():policy.firstOptions(monthly.price().amount()).stream().map(this::configChoice).toList());
            // R-04-14: the TODAY choice of an added dog always exists; ALTERNATIVE only up to billing.upfrontCutoffDay.
            if(me!=null&&me.planId!=null) upfront.put("additionalDogOptions",policy.additionalOptions(me.planId,me.planId).stream().map(this::configChoice).toList());
            // M5: one quote per plan, computed like the submission; the add-dog mode also quotes the member's own (maybe hidden) plan.
            var quoted=new ArrayList<>(plans);
            if(me!=null&&me.planId!=null&&plans.stream().noneMatch(p -> p.id().equals(me.planId))) policy.assignablePlans().stream().filter(p -> p.id().equals(me.planId)).findFirst().ifPresent(quoted::add);
            upfront.put("planQuotes",quoted.stream().map(plan -> quoteView(policy.planQuote(plan,me!=null,me==null?null:me.planId))).toList());
            result.put("upfront",upfront);
        }
        if(me!=null) result.put("member",object("planId",me.planId,"paymentMethodMasked",billing()?queries.payment(me):null,"consentsUpToDate",policy.currentConsent(history(me),legalVersion())));
        return result;
    }
    /**
     * `GET /signup.texts` with the placeholders resolved on the server (M6): `{deadlineDay}` is the day S13 returns as
     * `deadlineDay` (`inactivity.requestDeadlineDay`) and `{twoDogsMonthlyFee}` the current monthly price of the R-04-13 family
     * fare, formatted in the text's locale. Without a family fare the family intro is `null`.
     */
    private Map<String,Object> texts(String language) {
        var fare=policy.familyFare(2);
        String deadline=String.valueOf(access.config().get("inactivity.requestDeadlineDay",Integer.class));
        return object("freeTrainingConditions",textParameter("signup.text.freeTrainingConditions"),"therapyIntro",textParameter("signup.text.therapyIntro"),
                "familyGroupIntro",fare.map(plan -> textParameter("signup.text.familyGroupIntro").replace("{twoDogsMonthlyFee}",plan.price().amount().format(Locale.forLanguageTag(language)))).orElse(null),
                "monthlyPaymentIntro",textParameter("signup.text.monthlyPaymentIntro"),"paymentDay",textParameter("signup.text.paymentDay").replace("{deadlineDay}",deadline),
                "cashConditions",textParameter("signup.text.cashConditions"),"imageConsent",textParameter("signup.text.imageConsent"));
    }
    private Map<String,Object> quoteView(SignupPolicy.PlanQuote quote) {
        return object("planId",quote.planId(),"lines",quote.lines().stream().map(l -> object("concept",l.concept(),"amount",l.amountDue())).toList(),"totalDue",quote.totalDue(),
                "options",quote.options().stream().map(o -> object("option",o.option(),"portion",o.portion(),"startDate",o.startDate(),"amountDue",o.amountDue(),"totalDue",o.totalDue())).toList());
    }
    private Map<String,Object> configChoice(SignupPolicy.Period period) { return object("option",period.option(),"startDate",period.startDate(),"amount",period.amountDue()); }
    private String resolved(LocalizedText value) { return value==null?"":value.withDefaultLocale(access.config().club().defaultLocale()).resolve(locale()).value(); }
    private String textParameter(String key) {
        Object value=access.config().parameters().get(key);if(value==null) return "";
        if(value instanceof Map<?,?> values) return string(values.getOrDefault(locale(),null))==null?Objects.toString(values.get(access.config().club().defaultLocale()),""):string(values.get(locale()));
        return value.toString();
    }
    /** R-04-10 (E3-T14): the methods of the club's enabled providers, in the configured order; `payment(...)` accepts only these. */
    private List<String> offeredMethods() {
        return settings.providers().stream().map(PaymentProviderFlags::method).filter(Objects::nonNull).toList();
    }
    /**
     * The D2 method selector (R-04-19, web E3-W07 round 2): what D2 may assign is what `GET /signup` offers. The applicant's
     * current method is always listed, at the end and with `assignable = false` when its provider is off since. An add-dog
     * leaves the member ACTIVE: D2 then edits only the dogs (the method changes through D10), so only the current one is listed.
     */
    private List<Map<String,Object>> paymentOptions(Member member) {
        if(!billing()) return List.of();
        String language=locale();String current=string(map(effectivePayment(member)).get("type"));
        var options=new ArrayList<Map<String,Object>>();
        if("PENDING".equals(member.status)) offeredMethods().forEach(type -> options.add(object("type",type,"label",paymentLabel(type,language),"current",type.equals(current),"assignable",true)));
        if(current!=null&&options.stream().noneMatch(option -> current.equals(option.get("type")))) options.add(object("type",current,"label",paymentLabel(current,language),"current",true,"assignable",false));
        return options;
    }
    private String paymentLabel(String type,String language) {
        return messages.format("signup:payment.label."+type,Map.of(),Locale.forLanguageTag(language));
    }
    private String mandate(String language) {
        return messages.format("signup:payment.mandate."+countries.countryCode(),Map.of(),Locale.forLanguageTag(language))
                .replace("[[club_legal_name]]",string(settings.signupLegal(language).get("legalName")));
    }
    private Map<String,Object> planView(SignupPolicy.Plan plan) {
        return object("id",plan.id(),"type",plan.type(),"billingMode",plan.billingMode(),"name",resolved(plan.name()),"description",resolved(plan.description()),"conditions",resolved(plan.conditions()),
                "offerLabel",plan.offerLabel()==null?null:resolved(plan.offerLabel()),"price",plan.price(),"entryFee",plan.entryFee(),"pack",plan.pack(),"maintenanceFee",plan.maintenanceFee());
    }
    public List<String> warnings(Member member,List<Dog> dogs) {
        var warnings=new ArrayList<String>();
        // E38: a pending readmission is judged on what it submitted (the consent entries and the payment method it brings).
        var image=readmissionPending(member)?rows(submitted(member).get("consents")).stream().filter(r -> "IMAGE_USE".equals(r.get("type"))).reduce((a,b)->b).orElse(null):null;
        if(!Boolean.TRUE.equals(image!=null?image.get("granted"):map(map(member.consents).get("imageRights")).get("granted"))) warnings.add("NO_IMAGE_CONSENT");
        var payment=map(effectivePayment(member));
        // R-04-18 (E3-T10): a migrated SEPA member keeps only `ibanLast4` (the IBAN is encrypted); that account is provided.
        if(billing()&&"SEPA_DD".equals(payment.get("type"))&&payment.get("iban")==null&&payment.get("ibanLast4")==null) warnings.add("ACCOUNT_NOT_PROVIDED");
        if(dogs.stream().anyMatch(d -> documents.matching(Criteria.where("dogId").is(d.id).and("state").is("PENDING")).size()>0)) warnings.add("DOCUMENT_PENDING");
        if(access.enabled(Module.FAMILY_GROUP)&&"NOT_FOUND_PENDING".equals(map(member.familyGroupClaim).get("status"))) warnings.add("FAMILY_HOLDER_NOT_FOUND");
        if(billing()&&payments.due(member.id,scope(member,dogs),currency()).amountMinor()>0) warnings.add("UPFRONT_UNPAID");
        // The submission under review says whether it is a readmission (an add-dog block never is).
        if(Boolean.TRUE.equals(submission(member,dogs).get("readmission"))) warnings.add("READMISSION");
        return warnings;
    }
    private SignupPolicy.Plan proposedPlan(Member member,Map<String,Object> request,List<Dog> dogs) {
        String id=string(request.getOrDefault("planId",submission(member,dogs).getOrDefault("planIdRequested",member.planId)));
        // M8: the admin assigns any active plan of an enabled module, hidden from the public offer or not.
        var plan=policy.requireAssignable(id);
        if(request.get("planId")==null&&access.enabled(Module.FAMILY_GROUP)) {
            String holderId=string(map(member.familyGroupClaim).get("holderMemberId"));
            int count=dogs.size();
            if(holderId!=null) count+=dogs(holderId).stream().filter(d -> !"INACTIVE".equals(d.status)).count();
            else if("ACTIVE".equals(member.status)) count=dogs(member.id).stream().filter(d -> !"INACTIVE".equals(d.status)).toList().size();
            if(count>=2&&plan!=null&&"MONTHLY_FEE".equals(plan.billingMode())) plan=policy.proposedFamilyFare(count).orElse(plan);
        }
        // The accepted price is the one the plan bills (its billing mode): Teràpia's maintenance price, the standard one otherwise.
        if(request.get("priceId")!=null&&(plan==null||plan.billedPrice()==null||!request.get("priceId").equals(plan.billedPrice().id()))) throw new ApiException(ErrorCode.PLAN_NOT_AVAILABLE);
        return plan;
    }
    private SignupPolicy.Quote validationQuote(Member member,SignupPolicy.Plan plan,List<Dog> dogs) {
        boolean add="ACTIVE".equals(member.status);var signup=map(member.signup);
        var lines=new ArrayList<SignupPolicy.Line>();Money total=new Money(0,currency());SignupPolicy.Period first=null,additional=null;
        for(var dog:dogs) {
            var submittedSignup=dog.signup==null?signup:dog.signup;
            LocalDate submitted=instant(submittedSignup.getOrDefault("submittedAt",clock.instant())).atZone(ZoneId.of(access.config().club().timeZone())).toLocalDate();
            var quote=policy.quote(plan==null?null:plan.id(),dog.id,add,member.planId,string(submittedSignup.getOrDefault(add?"additionalDogOption":"firstMonthOption","TODAY")),submitted);
            lines.addAll(quote.lines());total=total.plus(quote.totalDue());first=quote.firstMonth();additional=quote.additionalDog();
        }
        return new SignupPolicy.Quote(lines,total,first,additional);
    }
    private Map<String,Object> reviewUpfront(Member member,List<Dog> dogs) {
        var scope=scope(member,dogs);var lines=payments.lines(member.id,scope);
        return object("lines",lineViews(lines),"totalDue",payments.due(member.id,scope,currency()),"totalPaid",payments.paid(member.id,scope,currency()),
                "firstMonth",chargesFirstMonth(lines)?firstMonthView(frozenFirstMonth(member,dogs)):null);
    }
    /**
     * `upfront.firstMonth` of D2 and of its dry run (web E3-W07, R-04-15): the month the FIRST_MONTH line pays, so D2 names
     * it and «(mitja quota)» without the rule. Absent without a FIRST_MONTH line.
     */
    private static Map<String,Object> firstMonthView(SignupPolicy.Period value) {
        return value==null?null:object("option",value.option(),"portion",value.portion(),"startDate",value.startDate(),"amountDue",value.amountDue());
    }
    public Map<String,Object> review(String id) {
        var member=access.mutableMember(id);var dogs=pending(id);if(dogs.isEmpty()) throw notPending();
        var plan=proposedPlan(member,Map.of(),dogs);var quote=validationQuote(member,plan,dogs);var signup=submission(member,dogs);var first=firstMonth(member,plan,dogs,quote);
        var submission=new LinkedHashMap<>(signup);submission.put("pendingDays",Math.max(0,ChronoUnit.DAYS.between(instant(signup.get("submittedAt")).atZone(ZoneId.of(access.config().club().timeZone())).toLocalDate(),policy.today())));
        var claim=new LinkedHashMap<>(map(member.familyGroupClaim));String holderId=string(claim.remove("holderMemberId"));
        if(holderId!=null) { var holder=access.members.require(holderId);claim.put("holder",object("id",holder.id,"fullName",fullName(holder),"isHolder",true)); }
        return object("member",queries.member(id,true),"dogs",dogs.stream().map(this::dogView).toList(),"signup",select(submission,"submittedAt","pendingDays","readmission","source","locale","planIdRequested"),
                "familyGroupClaim",access.enabled(Module.FAMILY_GROUP)?claim:null,"upfront",billing()?reviewUpfront(member,dogs):null,
                "proposals",object("planId",plan==null?null:plan.id(),"priceId",plan==null||plan.billedPrice()==null?null:plan.billedPrice().id(),"familyGroupId",member.familyGroupId,
                "nextInvoiceDate",first==null?member.nextInvoiceDate:policy.nextInvoice(first),"levels",access.levels()?access.references.activeLevelIds().stream().map(queries::level).toList():List.of()),"warnings",warnings(member,dogs),
                // M8: the D2 plan selector; M11: D2 shows the age warning without calling /dashboard.
                "planOptions",policy.assignablePlans().stream().map(this::planOption).toList(),"paymentMethods",paymentOptions(member),"warnDays",access.config().get("dashboard.pendingSignupAgeWarnDays",Integer.class),"version",member.version(),
                "readmission",readmissionPending(member)?readmissionView(member):null);
    }
    /** R-04-06 (E38): D2 shows the record and the request side by side, masked like the member view; never a full IBAN. */
    private Map<String,Object> readmissionView(Member member) {
        var view=submittedView(member);var current=readmissionValues(member);var requested=readmissionValues(view);
        var changed=READMISSION_FIELDS.stream().filter(field -> !Objects.equals(comparable(member,field),comparable(view,field))).toList();
        var previous=map(map(member.readmissionRequest).get("previous"));
        return object("current",current,"submitted",requested,"changedFields",changed,
                "consents",rows(submitted(member).get("consents")).stream().map(r -> object("type",r.get("type"),"granted",r.get("granted"),"version",r.get("version"))).toList(),
                "previousLeftAt",instant(previous.get("leftAt")),"previousLeftReason",previous.get("leftReason"));
    }
    /** The raw value compared for `changedFields` (the payment method without its signature date, which always differs). */
    private static Object comparable(Member member,String field) {
        return switch(field) {
            case "firstName" -> member.firstName; case "lastName1" -> member.lastName1; case "lastName2" -> member.lastName2; case "gender" -> member.gender;
            case "birthDate" -> member.birthDate;
            case "contactEmails" -> rows(member.contactEmails).stream().map(row -> row.get("email")).toList();
            case "phones" -> rows(member.phones).stream().map(row -> select(row,"prefix","number","label")).toList();
            case "address" -> select(map(member.address),"street","postalCode","city","province","country");
            default -> select(map(member.paymentMethod),"type","iban","holderName","holderTaxId","channel");
        };
    }
    private Map<String,Object> readmissionValues(Member member) {
        return object("firstName",member.firstName,"lastName1",member.lastName1,"lastName2",member.lastName2,"gender",member.gender,"birthDate",member.birthDate,
                "contactEmails",rows(member.contactEmails).stream().map(row -> object("email",row.get("email"),"bounced",Boolean.TRUE.equals(row.get("bounced")))).toList(),
                "phones",rows(member.phones).stream().map(row -> select(row,"prefix","number","label")).toList(),
                "address",member.address==null?null:select(map(member.address),"street","postalCode","city","province","country"),
                "paymentMethod",billing()?queries.payment(member):null);
    }
    /** A D2 plan option offers the price validation accepts and `member.priceId` stores: the plan's billed price. */
    private Map<String,Object> planOption(SignupPolicy.Plan plan) {
        var billed=plan.billedPrice();
        return object("planId",plan.id(),"name",resolved(plan.name()),"type",plan.type(),
                "prices",plan.prices().stream().filter(p -> billed!=null&&p.priceId().equals(billed.id())).map(p -> object("priceId",p.priceId(),"amount",p.amount(),"periodicity",p.periodicity(),"concept",p.concept())).toList());
    }
    private Map<String,Object> dogView(Dog dog) {
        // M12: the dog's own version is the one `PATCH /dogs/{id}` compares.
        return object("id",dog.id,"name",dog.name,"sex",dog.sex,"breed",dog.breed,"birthMonth",YearMonth.from(dog.birthDate).toString(),"chip",dog.chip,"notesToInstructors",map(dog.instructorNote).get("text"),"status",dog.status,"levelId",dog.levelId,
                "documents",documents.matching(Criteria.where("dogId").is(dog.id)).stream().map(d -> object("type",d.type,"state",d.state,"files",rows(d.files).stream().map(f -> object("name",f.get("name"),"downloadUrl",attachments.url(string(f.get("fileKey")),string(f.get("name"))))).toList())).toList(),
                "version",dog.version());
    }
    private static boolean planChanged(Member member,SignupPolicy.Plan plan,List<Dog> dogs) {
        String selected=plan==null?null:plan.id();
        return dogs.stream().anyMatch(d -> !Objects.equals(selected,map(d.signup==null?member.signup:d.signup).get("planIdRequested")));
    }
    /**
     * The first month in force (E3-T10, review of E3-T08 round 2): while the plan is the requested one, the rows and their
     * dates are those frozen at submission (`signup.upfront.firstMonth`), not a start recalculated from today's parameters
     * (a later `signup.firstMonthSplitDay`). A plan change recalculates everything (S04 §5).
     */
    private SignupPolicy.Period firstMonth(Member member,SignupPolicy.Plan plan,List<Dog> dogs,SignupPolicy.Quote quote) {
        if(planChanged(member,plan,dogs)) return quote.firstMonth();
        var frozen=frozenFirstMonth(member,dogs);
        return frozen!=null?frozen:quote.firstMonth();
    }
    /**
     * `signup.upfront.firstMonth` as frozen by the submission of these dogs, or null. E3-T12: with its `portion`. A block
     * frozen before the portion was stored gets the one its frozen amount charged against the plan's monthly price on the
     * submission day, or null ({@link SignupPolicy#frozenPortion}); never one from today's split day (round 2).
     */
    private SignupPolicy.Period frozenFirstMonth(Member member,List<Dog> dogs) {
        for(var dog:dogs) {
            var block=block(member,dog);var frozen=map(map(block.get("upfront")).get("firstMonth"));
            if(frozen.get("startDate")==null) continue;
            String option=string(frozen.get("option"));LocalDate start=LocalDate.parse(string(frozen.get("startDate")));
            var amount=mapper.convertValue(frozen.get("amountDue"),Money.class);
            String portion=frozen.get("portion")!=null?string(frozen.get("portion")):policy.frozenPortion(string(block.get("planIdRequested")),
                    block.get("submittedAt")==null?null:instant(block.get("submittedAt")).atZone(ZoneId.of(access.config().club().timeZone())).toLocalDate(),amount);
            return new SignupPolicy.Period(option,start,amount,portion);
        }
        return null;
    }
    /** Step 5 / round 2: an answer names a first month only when its own payment lines charge one. */
    private static boolean chargesFirstMonth(List<UpfrontPayments.Line> lines) { return lines.stream().anyMatch(l -> "FIRST_MONTH".equals(l.concept())); }
    /** §3 `Member.nextInvoiceDate` ≥ the first-month start in force (M21); only with BILLING and a first month. */
    private void nextInvoiceDate(Map<String,Object> request,SignupPolicy.Period first) {
        if(!billing()||request.get("nextInvoiceDate")==null||first==null) return;
        LocalDate date;
        try { date=date(request.get("nextInvoiceDate")); } catch(RuntimeException invalidDate) { throw invalid("nextInvoiceDate","INVALID_VALUE"); }
        if(date.isBefore(first.startDate())) throw invalid("nextInvoiceDate","INVALID_VALUE");
    }
    public Map<String,Object> dryRun(String id,Map<String,Object> request) {
        var member=access.mutableMember(id);var dogs=selection(member,request);var plan=proposedPlan(member,request,dogs);var quote=validationQuote(member,plan,dogs);
        var first=firstMonth(member,plan,dogs,quote);nextInvoiceDate(request,first);
        var warnings=new ArrayList<>(warnings(member,dogs));Map<String,Object> upfront=null;
        if(billing()) {
            // The preview is what the validation will write: the rows as they are, or (plan change, S04 §5 / E39) the kept rows plus the new ones.
            var scope=scope(member,dogs);List<UpfrontPayments.Line> lines;Money due,paid,exceeds=null;
            if(planChanged(member,plan,dogs)) {
                var replacement=payments.replacement(id,scope,charges(quote));lines=replacement.lines();due=replacement.due(currency());paid=replacement.paid(currency());
                if(replacement.checkoutPending()) warnings.add("CHECKOUT_PENDING");
                // E39b: what was paid beyond the new quote stays paid; D2 warns so the club refunds it (S12).
                exceeds=replacement.paidExceedsQuote();if(exceeds!=null) warnings.add("PAID_EXCEEDS_QUOTE");
            } else { lines=payments.lines(id,scope);due=payments.due(id,scope,currency());paid=payments.paid(id,scope,currency()); }
            upfront=object("lines",lineViews(lines.stream().map(l -> l.id()!=null?l:new UpfrontPayments.Line(UUID.nameUUIDFromBytes((id+":"+l.dogId()+":"+l.concept()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                    l.concept(),l.dogId(),l.amount(),l.paidAmount(),l.status(),l.provider(),l.submissionId())).toList()),"totalDue",due,"totalPaid",paid,"paidExceedsQuote",exceeds,
                    // The first month the validation will charge: the frozen one, or the recalculated one after a plan change;
                    // only when these lines charge one (round 2: BILLING enabled after a submission left no rows).
                    "firstMonth",chargesFirstMonth(lines)?firstMonthView(first):null);
        }
        return object("upfront",upfront,"price",billing()&&plan!=null?plan.billedPrice():null,"nextInvoiceDate",request.get("nextInvoiceDate")!=null?request.get("nextInvoiceDate"):first==null?member.nextInvoiceDate:policy.nextInvoice(first),"warnings",warnings);
    }
    private List<Dog> selection(Member member,Map<String,Object> request) {
        if(!Set.of("PENDING","ACTIVE").contains(member.status)) throw notPending();
        var pending=pending(member.id);if(pending.isEmpty()) throw notPending();
        version(member.version(),request.get("version"));var ids=rows(request.get("dogs")).stream().map(d -> string(d.get("dogId"))).toList();
        if(ids.isEmpty()||new HashSet<>(ids).size()!=ids.size()||!new HashSet<>(dogIds(pending)).containsAll(ids)) throw new ApiException(ErrorCode.INVALID_STATE);
        if("PENDING".equals(member.status)&&ids.size()!=pending.size()) throw new ApiException(ErrorCode.INVALID_STATE);
        return pending.stream().filter(d -> ids.contains(d.id)).toList();
    }
    @Transactional
    @Audited(action=AuditAction.MEMBER_VALIDATED,entityType="'Member'",entity="#id",member="#id")
    public Map<String,Object> validateNew(String id,Map<String,Object> request) { return validate(id,request,false); }
    @Transactional
    @Audited(action=AuditAction.SIGNUP_EDITED,entityType="'Member'",entity="#id",member="#id",reason="'Validate pending dogs'")
    public Map<String,Object> validateDogs(String id,Map<String,Object> request) { return validate(id,request,true); }
    private Map<String,Object> validate(String id,Map<String,Object> request,boolean addDog) {
        lock();var member=access.mutableMember(id);var dogs=selection(member,request);
        if(addDog!= "ACTIVE".equals(member.status)) throw new ApiException(ErrorCode.INVALID_STATE);
        var plan=proposedPlan(member,request,dogs);var quote=validationQuote(member,plan,dogs);nextInvoiceDate(request,firstMonth(member,plan,dogs,quote));
        var levels=new LinkedHashMap<String,String>();
        if(access.levels()) for(var raw:rows(request.get("dogs"))) {
            if(raw.get("levelId")==null) throw new ApiException(ErrorCode.LEVEL_REQUIRED);
            if(!Boolean.TRUE.equals(access.references.level(string(raw.get("levelId"))).get("active"))) throw new ApiException(ErrorCode.LEVEL_NOT_ACTIVE);
            levels.put(string(raw.get("dogId")),string(raw.get("levelId")));
        }
        if(billing()&&plan!=null&&"MONTHLY".equals(plan.type())&&!addDog&&request.get("nextInvoiceDate")==null) throw new ApiException(ErrorCode.NEXT_INVOICE_DATE_REQUIRED);
        String selectedId=plan==null?null:plan.id();var scope=scope(member,dogs);
        Money exceeds=planChanged(member,plan,dogs)?payments.replace(id,scope,charges(quote)).paidExceedsQuote():null;
        Money due=payments.due(id,scope,currency());
        if(billing()&&due.amountMinor()>0&&payments.lines(id,scope).stream().noneMatch(l -> "STRIPE".equals(l.provider())&&l.paidAmount().amountMinor()>0)&&request.get("upfrontAmountPaid")==null) throw invalid("upfrontAmountPaid","REQUIRED");
        if(request.get("upfrontAmountPaid")!=null) {
            if(!billing()) throw invalid("upfrontAmountPaid","MODULE_DISABLED");
            payments.allocate(id,scope,mapper.convertValue(request.get("upfrontAmountPaid"),Money.class));
        }
        if(!addDog&&readmissionPending(member)) applyReadmission(member);
        if(access.enabled(Module.FAMILY_GROUP)) joinFamily(member,request);
        member.planId=selectedId;member.priceId=plan==null||plan.billedPrice()==null?null:plan.billedPrice().id();
        if(request.get("nextInvoiceDate")!=null&&billing()) member.nextInvoiceDate=date(request.get("nextInvoiceDate"));
        boolean readmission=Boolean.TRUE.equals(map(member.signup).get("readmission"));
        if(!addDog) {
            var privacy=history(member).stream().filter(c -> c.type().equals("PRIVACY_POLICY")&&c.granted()).reduce((a,b)->b).orElseThrow(() -> invalid("consents","REQUIRED"));
            // R-04-22 / R-04-06 (d): a member with an account keeps it; only one without is matched or created by its primary email.
            member.accountId=identities.validate(id,member.accountId,email(member),fullName(member),string(member.signup.get("locale")),privacy.version(),privacy.acceptedAt(),readmission);
            if(member.memberNumber==null) member.memberNumber=settings.nextMemberNumber(access.members.matching(new Criteria()).stream().map(m -> m.memberNumber).filter(Objects::nonNull).max(Integer::compareTo).orElse(0)+1);
            member.status="ACTIVE";member.joinedAt=clock.instant();member.leftAt=null;member.leftReason=null;member.leaveDate=null;
        }
        if("SEPA_DD".equals(map(member.paymentMethod).get("type"))&&member.paymentMethod.get("mandateRef")==null) { member.paymentMethod=new LinkedHashMap<>(member.paymentMethod);member.paymentMethod.put("mandateRef","AH-"+member.id); }
        // S04 §3 (E3-T10): the decision is stamped on the public signup and on each validated dog's own submission block.
        var decision=object("validatedAt",clock.instant(),"validatedByAccountId",CurrentUser.current()==null?null:CurrentUser.current().accountId());
        if(!addDog) member.signup=stamped(map(member.signup),decision);
        access.members.save(member);
        for(var dog:dogs) { dog.status="ACTIVE";dog.registeredAt=clock.instant();dog.signup=stamped(block(member,dog),decision);if(!access.levels()) { dog.levelId=null;dog.levelAssignedAt=null; }access.dogs.save(dog); }
        // The first level is an assignment (history and audit, never DogLevelChanged); the events read the dogs as stored.
        for(var entry:levels.entrySet()) dogService.getObject().signupLevel(entry.getKey(),entry.getValue());
        var stored=dogs.stream().map(d -> access.dogs.require(d.id)).toList();
        if(!addDog) events.emit("MemberValidated","Member",id,object("memberId",id,"memberNumber",member.memberNumber,"dogs",stored.stream().map(SignupService::dogLevel).toList(),"nextInvoiceDate",member.nextInvoiceDate,"upfrontPaymentIds",payments.lines(id,scope).stream().map(UpfrontPayments.Line::id).toList(),"familyGroupId",member.familyGroupId,"readmission",readmission));
        else for(var dog:stored) events.emit("DogRegistered","Dog",dog.id,object("dogId",dog.id,"memberId",id,"levelId",dog.levelId));
        refreshDashboard();
        return object("memberId",id,"number",member.memberNumber,"accountId",member.accountId,"dogIds",dogIds(dogs),
                "warnings",exceeds==null?List.of():List.of("PAID_EXCEEDS_QUOTE"),"paidExceedsQuote",exceeds);
    }
    /** `MemberValidated.dogs[{dogId, levelId}]` from the stored dog (`levelId` null without levels), never from the request. */
    private static Map<String,Object> dogLevel(Dog dog) { var row=new LinkedHashMap<String,Object>();row.put("dogId",dog.id);row.put("levelId",dog.levelId);return row; }
    private void joinFamily(Member member,Map<String,Object> request) {
        String groupId=string(request.get("familyGroupId"));String holderId=string(map(member.familyGroupClaim).get("holderMemberId"));FamilyGroup group=null;
        if(groupId!=null) group=access.groups.require(groupId);
        else if(holderId!=null) {
            var holder=access.mutableMember(holderId);if(!Set.of("PENDING","ACTIVE").contains(holder.status)) throw new ApiException(ErrorCode.FAMILY_HOLDER_NOT_FOUND);
            if(holder.familyGroupId!=null) group=access.groups.require(holder.familyGroupId);
            else { group=new FamilyGroup();group.id=UUID.randomUUID().toString();group.clubId=TenantContext.require();group.holderMemberId=holderId;group.memberIds=new ArrayList<>(List.of(holderId));group.status="ACTIVE";access.groups.insert(group);holder.familyGroupId=group.id;access.members.save(holder); }
        }
        if(group==null) return;
        if(!"ACTIVE".equals(group.status)) throw new ApiException(ErrorCode.INVALID_STATE);
        if(member.familyGroupId!=null&&!member.familyGroupId.equals(group.id)) throw new ApiException(ErrorCode.FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP);
        var ids=new ArrayList<>(group.memberIds);if(!ids.contains(member.id)) ids.add(member.id);group.memberIds=ids;access.groups.save(group);member.familyGroupId=group.id;
    }
    @Transactional
    @Audited(action=AuditAction.SIGNUP_REJECTED,entityType="'Member'",entity="#id",member="#id",reason="#reason")
    public Map<String,Object> reject(String id,long expected,String reason) {
        lock();var member=access.mutableMember(id);var dogs=pending(id);
        if(!Set.of("PENDING","ACTIVE").contains(member.status)||dogs.isEmpty()) throw notPending();
        version(member.version(),expected);reason=text(reason,"reason",500,true);if(reason.length()<3) throw invalid("reason","INVALID_VALUE");
        boolean active="ACTIVE".equals(member.status);var scope=scope(member,dogs);
        // E3-T12: N-03 speaks the rejected submission's language, frozen in the event (a readmission restores the old signup).
        String locale=string(submission(member,dogs).get("locale"));
        // S04 §3 (E3-T10): the decision is stamped on each rejected dog's submission block and, for a new member, on its signup.
        // A rejected readmission gets the LEFT record's own signup back (E38): its decision stays on the dogs' blocks.
        var decision=object("rejectedAt",clock.instant(),"rejectedByAccountId",CurrentUser.current()==null?null:CurrentUser.current().accountId(),"rejectionReason",reason);
        for(var dog:dogs) dog.signup=stamped(block(member,dog),decision);
        var applicant=!active&&readmissionPending(member)?applicant(member):null;
        if(applicant!=null) restoreLeft(member);
        else if(!active) { member.status="LEFT";member.leftAt=clock.instant();member.leftReason="SIGNUP_REJECTED";member.familyGroupClaim=object("status","NONE");member.signup=stamped(map(member.signup),decision); }
        access.members.save(member);
        for(var dog:dogs) { dog.status="INACTIVE";dog.deactivationReason="SIGNUP_REJECTED";dog.deactivatedAt=clock.instant();access.dogs.save(dog); }
        boolean paid=payments.reject(id,scope);
        events.emit("SignupRejected","Member",id,object("memberId",id,"dogIds",dogIds(dogs),"reason",reason,"memberWasActive",active,"applicant",applicant,"locale",locale));
        refreshDashboard();
        return object("memberId",id,"status",member.status,"dogIds",dogIds(dogs),"paidPaymentRequiresRefund",paid);
    }

}
