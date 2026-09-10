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
    private record CachedConfig(Instant expires,Map<String,Object> value) { }
    private final java.util.concurrent.ConcurrentHashMap<String,CachedConfig> cache=new java.util.concurrent.ConcurrentHashMap<>();
    public void invalidateConfiguration(String clubId) { cache.keySet().removeIf(key -> key.startsWith(clubId+":")); }
    public SignupService(CensusAccess access,SignupPolicy policy,CensusEvents events,CensusRepository<DogDocument> documents,
            AttachmentService attachments,UpfrontPayments payments,CensusClubSettings settings,SignupIdentityService identities,
            SignupCapabilities capabilities,CountryContacts countries,Clock clock,CensusQuery queries,ObjectMapper mapper,IcuMessageSource messages) {
        this.access=access;this.policy=policy;this.events=events;this.documents=documents;this.attachments=attachments;this.payments=payments;
        this.settings=settings;this.identities=identities;this.capabilities=capabilities;this.countries=countries;this.clock=clock;this.queries=queries;this.mapper=mapper;this.messages=messages;
    }
    public void lock() { access.members.lock();policy.lock(); }
    private boolean billing() { return access.enabled(Module.BILLING); }
    private String currency() { return access.config().club().currency(); }
    private String locale() { return LocaleContext.current().getLanguage(); }
    private String legalVersion() { return string(settings.signupLegal(locale()).get("legalTextsVersion")); }
    private String fullName(Member m) { return String.join(" ",m.firstName,m.lastName1,m.lastName2==null?"":m.lastName2).strip(); }
    private String email(Member m) { return string(rows(m.contactEmails).getFirst().get("email")); }
    public Map<String,Object> member(String memberId) {
        var m=access.mutableMember(memberId);return object("id",m.id,"email",email(m),"locale",map(m.signup).getOrDefault("locale",access.config().club().defaultLocale()),"paymentMethod",m.paymentMethod,"status",m.status);
    }
    public void authorize(String id,String token) {
        if(CurrentUser.current()==null) { capabilities.require(id,token); }
        else if(!access.role("ADMIN") && !access.me().id.equals(id)) { throw new ApiException(ErrorCode.FORBIDDEN); }
        access.mutableMember(id);
    }
    public void card(String id,Map<String,Object> card) {
        var member=access.mutableMember(id);
        if("CARD".equals(map(member.paymentMethod).get("type"))) { member.paymentMethod=object("type","CARD","card",card);access.members.save(member); }
    }
    private void enabled() {
        if(!"ACTIVE".equals(access.config().club().status()) || !Boolean.TRUE.equals(access.config().get("signup.enabled",Boolean.class))) { throw new ApiException(ErrorCode.SIGNUP_CLOSED); }
    }
    private List<Dog> dogs(String memberId) { return access.dogs.matching(Criteria.where("memberId").is(memberId)); }
    private List<Dog> pending(String memberId) { return dogs(memberId).stream().filter(d -> "PENDING".equals(d.status)).toList(); }
    private List<String> dogIds(List<Dog> dogs) { return dogs.stream().map(d -> d.id).toList(); }
    private Member match(String document,List<Map<String,Object>> emails,boolean left) {
        var candidates=access.members.matching(Criteria.where("status").in(left?List.of("LEFT"):List.of("PENDING","ACTIVE")));
        var exact=candidates.stream().filter(m -> document.equals(map(m.idDocument).get("number"))).findFirst().orElse(null);
        if(exact!=null || left) return exact;
        var addresses=emails.stream().map(e -> e.get("email")).toList();
        return candidates.stream().filter(m -> !rows(m.contactEmails).isEmpty() && addresses.contains(rows(m.contactEmails).getFirst().get("email"))).findFirst().orElse(null);
    }
    @Transactional
    public Map<String,Object> identityCheck(Map<String,Object> request) {
        var id=map(request.get("idDocument"));String number=policy.document(string(id.get("type")),string(id.get("value")));
        var emails=policy.emails(strings(request.get("emails")));var m=match(number,emails,false);
        if(m==null) return object("result","NEW");
        if("PENDING".equals(m.status)) return object("result","SIGNUP_ALREADY_PENDING");
        if(m.accountId==null) return object("result","CONTACT_CLUB");
        events.emit("SignupRecognitionRequested","Member",m.id,object("memberId",m.id,"accountId",m.accountId,"redirect","/gossos/nou"));
        String address=email(m);int at=address.indexOf('@');
        String masked=address.substring(0,1)+"•••@"+address.substring(at+1,at+2)+"•••";
        return object("result","VERIFICATION_SENT","maskedEmail",masked);
    }
    @SuppressWarnings("unchecked") private List<String> strings(Object raw) { return (List<String>)raw; }
    private Optional<SignupPolicy.Match> holder(String holder,String dog) {
        var candidates=access.members.matching(Criteria.where("status").in("PENDING","ACTIVE")).stream().map(m -> new SignupPolicy.Candidate(m.clubId,m.id,m.status,m.firstName,m.lastName1,m.lastName2,
                dogs(m.id).stream().map(d -> new SignupPolicy.NamedDog(d.name,d.status)).toList())).toList();
        return policy.family(holder,dog,candidates);
    }
    public Map<String,Object> familyLookup(String name,String dog) { access.require(Module.FAMILY_GROUP);return holder(name,dog).map(m -> object("result","FOUND","holderDisplayName",m.holderDisplayName())).orElse(object("result","NOT_FOUND")); }
    private Map<String,Object> claim(Map<String,Object> raw) {
        if(raw.isEmpty()) return object("status","NONE");
        if(!access.enabled(Module.FAMILY_GROUP)) throw invalid("familyGroupClaim","MODULE_DISABLED");
        String name=text(raw.get("holderName"),"familyGroupClaim.holderName",120,true),dog=text(raw.get("dogName"),"familyGroupClaim.dogName",40,true);
        var match=holder(name,dog);
        if(match.isPresent()) return object("status","FOUND","holderName",name,"dogName",dog,"holderMemberId",match.get().holderMemberId());
        if(!Boolean.TRUE.equals(raw.get("leavePending"))) throw new ApiException(ErrorCode.FAMILY_HOLDER_NOT_FOUND);
        if(!Boolean.TRUE.equals(access.config().get("signup.allowFamilyGroupPending",Boolean.class))) throw invalid("familyGroupClaim.leavePending","NOT_ALLOWED");
        return object("status","NOT_FOUND_PENDING","holderName",name,"dogName",dog);
    }
    public void requirePlan(String planId) { policy.require(planId); }
    public List<SignupPolicy.Consent> history(Member member) {
        return ConsentLedgers.entries(member.consents).stream().map(r -> new SignupPolicy.Consent(string(r.get("type")),Boolean.TRUE.equals(r.get("granted")),string(r.get("version")),instant(r.get("acceptedAt")),string(r.get("locale")),string(r.get("ipHash")),string(r.get("source")))).toList();
    }
    private void consents(Member member,Map<String,Object> raw,boolean addDog,String locale,String ip) {
        var privacy=map(raw.get("privacyPolicy"));var image=map(raw.get("imageUse"));
        String version=legalVersion();
        if(!raw.isEmpty() && !version.equals(image.get("version"))) throw new ApiException(ErrorCode.CONSENT_VERSION_OUTDATED);
        var entries=policy.consent((Boolean)privacy.get("accepted"),string(privacy.get("version")),(Boolean)image.get("granted"),version,addDog,history(member),locale,capabilities.fingerprint(ip));
        if(entries.isEmpty()) return;
        var ledger=new ArrayList<>(ConsentLedgers.entries(member.consents));
        for(var entry:entries) ledger.add(object("type",entry.type(),"granted",entry.granted(),"version",entry.version(),"acceptedAt",entry.acceptedAt(),"locale",entry.locale(),"ipHash",entry.ipHash(),"source",entry.source()));
        member.consents=new ConsentLedgerConverter().read(ledger,null);
    }
    public Map<String,Object> payment(Map<String,Object> raw,Member member,String defaultHolder) {
        if(!billing()) { if(raw.get("iban")!=null) throw invalid("payment.iban","MODULE_DISABLED");return null; }
        String type=string(raw.get("type"));
        String provider=switch(String.valueOf(type)) { case "SEPA_DD" -> "SEPA_XML";case "CARD" -> "STRIPE";case "MANUAL" -> "MANUAL";default -> ""; };
        if(!settings.providerEnabled(provider)) throw new ApiException(ErrorCode.PAYMENT_METHOD_NOT_AVAILABLE);
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
    public Map<String,Object> submit(Map<String,Object> request,String ip) {
        enabled();lock();var person=map(request.get("person"));var id=map(person.get("idDocument"));
        String number=policy.document(string(id.get("type")),string(id.get("value")));var emails=policy.emails(strings(person.get("emails")));
        var existing=match(number,emails,false);
        if(existing!=null) throw new ApiException("PENDING".equals(existing.status)?ErrorCode.SIGNUP_ALREADY_PENDING:ErrorCode.MEMBER_ALREADY_EXISTS);
        var member=match(number,emails,true);boolean readmission=member!=null;
        if(member==null) { member=new Member();member.id=UUID.randomUUID().toString();member.clubId=TenantContext.require(); }
        if(member.erasedAt!=null) throw new ApiException(ErrorCode.MEMBER_ERASED);
        String locale=string(request.get("locale"));if(!access.config().club().locales().contains(locale)) throw invalid("locale","INVALID_VALUE");
        member.idDocument=object("type",id.get("type"),"number",number);member.firstName=text(person.get("firstName"),"firstName",60,true);member.lastName1=text(person.get("lastName1"),"lastName1",60,true);member.lastName2=text(person.get("lastName2"),"lastName2",60,false);
        member.gender=string(person.get("gender"));member.birthDate=date(person.get("birthDate"));
        if(member.birthDate.isAfter(policy.today())) throw invalid("birthDate","INVALID_VALUE");
        member.contactEmails=emails;member.phones=policy.phones(rows(person.get("phones")));
        var address=map(person.get("address"));member.address=object("street",address.get("street"),"postalCode",address.get("postalCode"),"city",policy.town(string(address.get("postalCode")),string(address.get("town"))),"country",countries.countryCode());
        member.familyGroupClaim=claim(map(request.get("familyGroupClaim")));
        String holderId=string(member.familyGroupClaim.get("holderMemberId"));
        member.paymentMethod=payment(map(request.get("payment")),member,holderId==null?fullName(member):fullName(access.members.require(holderId)));
        consents(member,map(request.get("consents")),false,locale,ip);
        String planId=string(request.get("planId"));policy.require(planId);
        String option=string(map(request.get("payment")).getOrDefault("firstMonthOption","TODAY"));
        member.status="PENDING";member.signup=object("submittedAt",clock.instant(),"locale",locale,"source","PUBLIC","readmission",readmission,"planIdRequested",planId,"firstMonthOption",option);
        if(readmission) access.members.save(member);else access.members.insert(member);
        var dog=createDog(member,map(request.get("dog")),rows(map(request.get("dog")).get("documents")),readmission);
        var quote=policy.quote(planId,dog.id,false,null,option,policy.today());createPayments(member.id,quote);
        boolean checkout=checkoutRequired(member,quote.totalDue());
        events.emit("SignupSubmitted","Member",member.id,object("memberId",member.id,"dogIds",List.of(dog.id),"planId",planId,"paymentMethodType",map(member.paymentMethod).get("type"),"source","PUBLIC","readmission",readmission,"checkoutRequired",checkout));
        return object("memberId",member.id,"signupToken",capabilities.issue(member.id),"upfront",billing()?upfront(member.id,List.of(dog.id),quote):null,"checkout",object("required",checkout));
    }
    private boolean checkoutRequired(Member m,Money due) { return billing()&&settings.providerEnabled("STRIPE")&&(due.amountMinor()>0 || "CARD".equals(map(m.paymentMethod).get("type"))); }
    private void createPayments(String member,SignupPolicy.Quote quote) { payments.create(member,quote.lines().stream().map(l -> new UpfrontPayments.Charge(l.concept(),l.dogId(),l.amountDue())).toList()); }
    private Map<String,Object> upfront(String id,List<String> dogs,SignupPolicy.Quote quote) {
        return object("lines",paymentLines(id,dogs),"totalDue",payments.due(id,dogs,currency()),"additionalDog",quote==null?null:quote.additionalDog());
    }
    public List<Map<String,Object>> paymentLines(String id,List<String> dogs) { return payments.lines(id,dogs).stream().map(l -> object("id",l.id(),"concept",l.concept(),"amount",l.amount(),"paidAmount",l.paidAmount(),"status",l.status(),"provider",l.provider())).toList(); }
    private Dog createDog(Member member,Map<String,Object> raw,List<Map<String,Object>> files,boolean readmission) {
        String chip=text(raw.get("chip"),"dog.chip",20,true);var matches=access.dogs.matching(Criteria.where("chip").is(chip));Dog dog=null;
        if(!matches.isEmpty()) { dog=matches.getFirst();if(!readmission || !member.id.equals(dog.memberId) || !"INACTIVE".equals(dog.status)) throw new ApiException(ErrorCode.DOG_CHIP_ALREADY_REGISTERED); }
        boolean fresh=dog==null;if(fresh) { dog=new Dog();dog.id=UUID.randomUUID().toString();dog.clubId=TenantContext.require();dog.memberId=member.id; }
        dog.name=text(raw.get("name"),"dog.name",40,true);dog.sex=string(raw.get("sex"));dog.breed=text(raw.get("breed"),"dog.breed",60,true);dog.birthDate=YearMonth.parse(string(raw.get("birthMonth"))).atDay(1);
        if(dog.birthDate.isAfter(policy.today())) throw invalid("dog.birthMonth","INVALID_VALUE");
        dog.signup=new LinkedHashMap<>(member.signup);
        dog.chip=chip;dog.status="PENDING";dog.deactivatedAt=null;dog.deactivationReason=null;
        dog.instructorNote=object("text",text(raw.get("notesToInstructors"),"dog.notesToInstructors",1000,false),"updatedAt",clock.instant());
        if(fresh) access.dogs.insert(dog);else access.dogs.save(dog);saveDocuments(dog,files);return dog;
    }
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
            doc.files=files;doc.state=files.isEmpty()?"PENDING":"RECEIVED";if(fresh) documents.insert(doc);else documents.save(doc);
            if(files.isEmpty()) events.emit("DogDocumentPending","DogDocument",doc.id,object("dogId",dog.id,"type",type,"state","PENDING"));
        }
    }
    @Transactional
    @Audited(action=AuditAction.SIGNUP_EDITED,entityType="'Dog'",entity="#result['dogId']",member="#memberId")
    public Map<String,Object> addDog(String memberId,Map<String,Object> request,String ip) {
        lock();var member=access.me();if(!"ACTIVE".equals(member.status)) throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE);
        if(member.erasedAt!=null) throw new ApiException(ErrorCode.MEMBER_ERASED);
        String planId=string(request.getOrDefault("planIdRequested",member.planId));policy.require(planId);
        consents(member,map(request.get("consents")),true,locale(),ip);
        String option=string(request.getOrDefault("additionalDogOption","TODAY"));
        member.signup=object("submittedAt",clock.instant(),"locale",locale(),"source","APP_ADD_DOG","readmission",false,"planIdRequested",planId,"additionalDogOption",option);
        access.members.save(member);var dog=createDog(member,map(request.get("dog")),rows(request.get("documents")),false);
        var quote=policy.quote(planId,dog.id,true,member.planId,option,policy.today());createPayments(member.id,quote);
        boolean checkout=checkoutRequired(member,quote.totalDue());
        events.emit("SignupSubmitted","Member",member.id,object("memberId",member.id,"dogIds",List.of(dog.id),"planId",planId,"paymentMethodType",map(member.paymentMethod).get("type"),"source","APP_ADD_DOG","readmission",false,"checkoutRequired",checkout));
        return object("dogId",dog.id,"memberId",member.id,"upfront",billing()?upfront(member.id,List.of(dog.id),quote):null,"checkout",object("required",checkout,"memberId",member.id));
    }
    public Map<String,Object> configuration() {
        if(CurrentUser.current()!=null) return configurationFor(access.me());
        String key=TenantContext.require()+":"+locale();var cached=cache.get(key);
        if(cached!=null&&cached.expires().isAfter(clock.instant())) return cached.value();
        var value=configurationFor(null);cache.put(key,new CachedConfig(clock.instant().plusSeconds(60),value));return value;
    }
    private Map<String,Object> configurationFor(Member me) {
        var config=access.config();String language=locale();
        if(!"ACTIVE".equals(config.club().status()) || !Boolean.TRUE.equals(config.get("signup.enabled",Boolean.class))) return object("enabled",false,"closedText",textParameter("signup.text.closed"));
        var plans=policy.plans();
        var legal=new LinkedHashMap<>(settings.signupLegal(language));legal.remove("legalName");
        var result=object("enabled",true,"steps",access.enabled(Module.FAMILY_GROUP)&&me==null?List.of("PERSON","DOG","FAMILY_GROUP","PAYMENT"):List.of("PERSON","DOG","PAYMENT"),
                "plans",plans.stream().map(this::planView).toList(),"texts",object("freeTrainingConditions",textParameter("signup.text.freeTrainingConditions"),"therapyIntro",textParameter("signup.text.therapyIntro"),
                "familyGroupIntro",textParameter("signup.text.familyGroupIntro"),"monthlyPaymentIntro",textParameter("signup.text.monthlyPaymentIntro"),"paymentDay",textParameter("signup.text.paymentDay"),
                "cashConditions",textParameter("signup.text.cashConditions"),"imageConsent",textParameter("signup.text.imageConsent")),"legal",legal,
                "countryProfile",countries.signupProfile());
        if(billing()) {
            result.put("paymentMethods",settings.providers().stream().map(provider -> switch(provider) {case "SEPA_XML" -> "SEPA_DD";case "STRIPE" -> "CARD";case "MANUAL" -> "MANUAL";default -> null;}).filter(Objects::nonNull).map(type -> object("type",type,"label",paymentLabel(type,language),
                    "instructions",type.equals("MANUAL")?textParameter("signup.text.cashConditions"):null,"mandateText",type.equals("SEPA_DD")?mandate(language):null)).toList());
            var monthly=plans.stream().filter(plan -> "MONTHLY_FEE".equals(plan.billingMode())&&plan.price()!=null).findFirst().orElse(null);
            var upfront=object("firstMonthSplitDay",config.get("signup.firstMonthSplitDay",Integer.class),"today",policy.today(),"firstMonthOptions",monthly==null?List.of():policy.firstOptions(monthly.price().amount()).stream().map(this::configChoice).toList());
            if(me!=null&&policy.today().getDayOfMonth()<=config.get("billing.upfrontCutoffDay",Integer.class)) upfront.put("additionalDogOptions",policy.additionalOptions(me.planId,me.planId).stream().map(this::configChoice).toList());
            result.put("upfront",upfront);
        }
        if(me!=null) result.put("member",object("planId",me.planId,"paymentMethodMasked",billing()?queries.payment(me):null,"consentsUpToDate",policy.currentConsent(history(me),legalVersion())));
        return result;
    }
    private Map<String,Object> configChoice(SignupPolicy.Period period) { return object("option",period.option(),"startDate",period.startDate(),"amount",period.amountDue()); }
    private String resolved(LocalizedText value) { return value==null?"":value.withDefaultLocale(access.config().club().defaultLocale()).resolve(locale()).value(); }
    private String textParameter(String key) {
        Object value=access.config().parameters().get(key);if(value==null) return "";
        if(value instanceof Map<?,?> values) return string(values.getOrDefault(locale(),null))==null?Objects.toString(values.get(access.config().club().defaultLocale()),""):string(values.get(locale()));
        return value.toString();
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
        if(!Boolean.TRUE.equals(map(map(member.consents).get("imageRights")).get("granted"))) warnings.add("NO_IMAGE_CONSENT");
        if(billing()&&"SEPA_DD".equals(map(member.paymentMethod).get("type"))&&map(member.paymentMethod).get("iban")==null) warnings.add("ACCOUNT_NOT_PROVIDED");
        if(dogs.stream().anyMatch(d -> documents.matching(Criteria.where("dogId").is(d.id).and("state").is("PENDING")).size()>0)) warnings.add("DOCUMENT_PENDING");
        if(access.enabled(Module.FAMILY_GROUP)&&"NOT_FOUND_PENDING".equals(map(member.familyGroupClaim).get("status"))) warnings.add("FAMILY_HOLDER_NOT_FOUND");
        if(billing()&&payments.due(member.id,dogIds(dogs),currency()).amountMinor()>0) warnings.add("UPFRONT_UNPAID");
        if(Boolean.TRUE.equals(map(member.signup).get("readmission"))) warnings.add("READMISSION");
        return warnings;
    }
    private SignupPolicy.Plan proposedPlan(Member member,Map<String,Object> request,List<Dog> dogs) {
        String id=string(request.getOrDefault("planId",map(member.signup).getOrDefault("planIdRequested",member.planId)));
        var plan=policy.require(id);
        if(request.get("planId")==null&&access.enabled(Module.FAMILY_GROUP)) {
            String holderId=string(map(member.familyGroupClaim).get("holderMemberId"));
            int count=dogs.size();
            if(holderId!=null) count+=dogs(holderId).stream().filter(d -> !"INACTIVE".equals(d.status)).count();
            else if("ACTIVE".equals(member.status)) count=dogs(member.id).stream().filter(d -> !"INACTIVE".equals(d.status)).toList().size();
            int eligible=count;
            if(count>=2&&plan!=null&&"MONTHLY_FEE".equals(plan.billingMode())) plan=policy.plans().stream().filter(p -> "MONTHLY_FEE".equals(p.billingMode())&&p.dogsIncluded()>=eligible&&p.price()!=null)
                    .min(Comparator.comparingInt(SignupPolicy.Plan::dogsIncluded)).orElse(plan);
        }
        if(request.get("priceId")!=null&&(plan==null||plan.price()==null||!request.get("priceId").equals(plan.price().id()))) throw new ApiException(ErrorCode.PLAN_NOT_AVAILABLE);
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
    private Map<String,Object> reviewUpfront(Member member,List<Dog> dogs) { return object("lines",paymentLines(member.id,dogIds(dogs)),"totalDue",payments.due(member.id,dogIds(dogs),currency()),"totalPaid",payments.paid(member.id,dogIds(dogs),currency())); }
    public Map<String,Object> review(String id) {
        var member=access.mutableMember(id);var dogs=pending(id);if(dogs.isEmpty()) throw new ApiException(ErrorCode.INVALID_STATE);
        var plan=proposedPlan(member,Map.of(),dogs);var quote=validationQuote(member,plan,dogs);var signup=map(member.signup);
        var submission=new LinkedHashMap<>(signup);submission.put("pendingDays",Math.max(0,ChronoUnit.DAYS.between(instant(signup.get("submittedAt")).atZone(ZoneId.of(access.config().club().timeZone())).toLocalDate(),policy.today())));
        var claim=new LinkedHashMap<>(map(member.familyGroupClaim));String holderId=string(claim.remove("holderMemberId"));
        if(holderId!=null) { var holder=access.members.require(holderId);claim.put("holder",object("id",holder.id,"fullName",fullName(holder),"isHolder",true)); }
        return object("member",queries.member(id,true),"dogs",dogs.stream().map(this::dogView).toList(),"signup",select(submission,"submittedAt","pendingDays","readmission","source","locale","planIdRequested"),
                "familyGroupClaim",access.enabled(Module.FAMILY_GROUP)?claim:null,"upfront",billing()?reviewUpfront(member,dogs):null,
                "proposals",object("planId",plan==null?null:plan.id(),"priceId",plan==null||plan.price()==null?null:plan.price().id(),"familyGroupId",member.familyGroupId,
                "nextInvoiceDate",quote.firstMonth()==null?member.nextInvoiceDate:policy.nextInvoice(quote.firstMonth()),"levels",access.levels()?access.references.activeLevelIds().stream().map(queries::level).toList():List.of()),"warnings",warnings(member,dogs),"version",member.version());
    }
    private Map<String,Object> dogView(Dog dog) {
        return object("id",dog.id,"name",dog.name,"sex",dog.sex,"breed",dog.breed,"birthMonth",YearMonth.from(dog.birthDate).toString(),"chip",dog.chip,"notesToInstructors",map(dog.instructorNote).get("text"),"status",dog.status,"levelId",dog.levelId,
                "documents",documents.matching(Criteria.where("dogId").is(dog.id)).stream().map(d -> object("type",d.type,"state",d.state,"files",rows(d.files).stream().map(f -> object("name",f.get("name"),"downloadUrl",attachments.url(string(f.get("fileKey")),string(f.get("name"))))).toList())).toList());
    }
    public Map<String,Object> dryRun(String id,Map<String,Object> request) {
        var member=access.mutableMember(id);var dogs=selection(member,request);var plan=proposedPlan(member,request,dogs);var quote=validationQuote(member,plan,dogs);
        Money paid=payments.paid(id,dogIds(dogs),currency());Money due=new Money(Math.max(0,quote.totalDue().minus(paid).amountMinor()),currency());
        return object("upfront",billing()?object("lines",quote.lines().stream().map(l -> object("id",UUID.nameUUIDFromBytes((id+":"+l.dogId()+":"+l.concept()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"concept",l.concept(),"amount",l.amountDue(),"status","DUE")).toList(),"totalDue",due,"totalPaid",paid):null,
                "price",billing()&&plan!=null?plan.price():null,"nextInvoiceDate",request.get("nextInvoiceDate")!=null?request.get("nextInvoiceDate"):quote.firstMonth()==null?member.nextInvoiceDate:policy.nextInvoice(quote.firstMonth()),"warnings",warnings(member,dogs));
    }
    private List<Dog> selection(Member member,Map<String,Object> request) {
        if(!Set.of("PENDING","ACTIVE").contains(member.status)) throw new ApiException(ErrorCode.INVALID_STATE);
        var pending=pending(member.id);if(pending.isEmpty()) throw new ApiException(ErrorCode.INVALID_STATE);
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
        var plan=proposedPlan(member,request,dogs);var quote=validationQuote(member,plan,dogs);
        if(access.levels()) for(var raw:rows(request.get("dogs"))) {
            if(raw.get("levelId")==null) throw new ApiException(ErrorCode.LEVEL_REQUIRED);
            if(!Boolean.TRUE.equals(access.references.level(string(raw.get("levelId"))).get("active"))) throw new ApiException(ErrorCode.LEVEL_NOT_ACTIVE);
        }
        if(billing()&&plan!=null&&"MONTHLY".equals(plan.type())&&!addDog&&request.get("nextInvoiceDate")==null) throw new ApiException(ErrorCode.NEXT_INVOICE_DATE_REQUIRED);
        String selectedId=plan==null?null:plan.id();
        if(dogs.stream().anyMatch(d -> !Objects.equals(selectedId,map(d.signup==null?member.signup:d.signup).get("planIdRequested")))) payments.replace(id,dogIds(dogs),quote.lines().stream().map(l -> new UpfrontPayments.Charge(l.concept(),l.dogId(),l.amountDue())).toList());
        Money due=payments.due(id,dogIds(dogs),currency());Money paid=payments.paid(id,dogIds(dogs),currency());
        if(billing()&&due.amountMinor()>0&&payments.lines(id,dogIds(dogs)).stream().noneMatch(l -> "STRIPE".equals(l.provider())&&l.paidAmount().amountMinor()>0)&&request.get("upfrontAmountPaid")==null) throw invalid("upfrontAmountPaid","REQUIRED");
        if(request.get("upfrontAmountPaid")!=null) {
            if(!billing()) throw invalid("upfrontAmountPaid","MODULE_DISABLED");
            payments.allocate(id,dogIds(dogs),mapper.convertValue(request.get("upfrontAmountPaid"),Money.class));
        }
        if(access.enabled(Module.FAMILY_GROUP)) joinFamily(member,request);
        member.planId=selectedId;member.priceId=plan==null||plan.price()==null?null:plan.price().id();
        if(request.get("nextInvoiceDate")!=null&&billing()) member.nextInvoiceDate=date(request.get("nextInvoiceDate"));
        boolean readmission=Boolean.TRUE.equals(map(member.signup).get("readmission"));
        if(!addDog) {
            var privacy=history(member).stream().filter(c -> c.type().equals("PRIVACY_POLICY")&&c.granted()).reduce((a,b)->b).orElseThrow(() -> invalid("consents","REQUIRED"));
            member.accountId=identities.validate(id,email(member),fullName(member),string(member.signup.get("locale")),privacy.version(),privacy.acceptedAt(),readmission);
            if(member.memberNumber==null) member.memberNumber=settings.nextMemberNumber(access.members.matching(new Criteria()).stream().map(m -> m.memberNumber).filter(Objects::nonNull).max(Integer::compareTo).orElse(0)+1);
            member.status="ACTIVE";member.joinedAt=clock.instant();member.leftAt=null;member.leftReason=null;member.leaveDate=null;
        }
        if("SEPA_DD".equals(map(member.paymentMethod).get("type"))&&member.paymentMethod.get("mandateRef")==null) { member.paymentMethod=new LinkedHashMap<>(member.paymentMethod);member.paymentMethod.put("mandateRef","AH-"+member.id); }
        access.members.save(member);
        for(var dog:dogs) { dog.status="ACTIVE";dog.registeredAt=clock.instant();if(!access.levels()) { dog.levelId=null;dog.levelAssignedAt=null; }access.dogs.save(dog); }
        if(!addDog) events.emit("MemberValidated","Member",id,object("memberId",id,"memberNumber",member.memberNumber,"dogs",rows(request.get("dogs")),"nextInvoiceDate",member.nextInvoiceDate,"upfrontPaymentIds",payments.lines(id,dogIds(dogs)).stream().map(UpfrontPayments.Line::id).toList(),"familyGroupId",member.familyGroupId,"readmission",readmission));
        else for(var dog:dogs) events.emit("DogRegistered","Dog",dog.id,object("dogId",dog.id,"memberId",id,"levelId",!access.levels()?null:rows(request.get("dogs")).stream().filter(d -> dog.id.equals(d.get("dogId"))).findFirst().orElseThrow().get("levelId")));
        return object("memberId",id,"number",member.memberNumber,"accountId",member.accountId,"dogIds",dogIds(dogs));
    }
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
        if(!Set.of("PENDING","ACTIVE").contains(member.status)||dogs.isEmpty()) throw new ApiException(ErrorCode.INVALID_STATE);
        version(member.version(),expected);reason=text(reason,"reason",500,true);if(reason.length()<3) throw invalid("reason","INVALID_VALUE");
        boolean active="ACTIVE".equals(member.status);
        if(!active) { member.status="LEFT";member.leftAt=clock.instant();member.leftReason="SIGNUP_REJECTED";member.familyGroupClaim=object("status","NONE"); }
        access.members.save(member);
        for(var dog:dogs) { dog.status="INACTIVE";dog.deactivationReason="SIGNUP_REJECTED";dog.deactivatedAt=clock.instant();access.dogs.save(dog); }
        boolean paid=payments.reject(id,dogIds(dogs));
        events.emit("SignupRejected","Member",id,object("memberId",id,"dogIds",dogIds(dogs),"reason",reason,"memberWasActive",active));
        return object("memberId",id,"status",member.status,"dogIds",dogIds(dogs),"paidPaymentRequiresRefund",paid);
    }

}
