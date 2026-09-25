package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E3-T12: the follow-ups of the E3-T09 round-2 review (`roadmap/reviews/E3-T09-20260925-0854-codex.md`) and the first
 * month of the D2 view (web E3-W07). Same Spring context as {@link SignupIT}; one fixture club with one 60 € monthly plan
 * and every module. Step 4 (the generation-aware configuration cache) is in {@code CacheInvalidationRaceIT}.
 */
@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SignupFollowUpFixesIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signupService;
    @Autowired com.agilityhub.core.shared.application.OutboxDispatcher dispatcher;
    @Autowired com.agilityhub.core.clubs.messaging.application.EmailSender sender;
    @Autowired com.agilityhub.core.payments.application.FakeCheckoutGateway fake;
    @Autowired com.agilityhub.core.clubs.census.application.SignupNotifications notifications;
    @Autowired com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmissionRepository admissions;
    String club,host,plan,level;
    int sequence;
    @BeforeEach void fixtureClub() {
        club="fu-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();level=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of("enabled",true),"SEPA_XML",Map.of("enabled",true))));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        plan("MONTHLY",plan,6000);
        mongo.insert(new Level(level,club,"A",new LocalizedText(Map.of("ca","Iniciació","es","Iniciación","en","Beginner"),"en"),0,"#000000",8,false,true,true,0,clock.instant(),clock.instant(),null,null));
        mailbox().clear();
    }
    void plan(String code,String id,long monthlyMinor) {
        var name=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(id,club,code,name,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,name,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,id,PriceConcept.MONTHLY_FEE,new Money(monthlyMinor,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        signupService.invalidateConfiguration(club);
    }
    String national() { int value=15000000+(++sequence);return String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23); }
    static String unique(String prefix) { return prefix+"-"+UUID.randomUUID()+"@example.test"; }
    ObjectNode request() {
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national()),"firstName","Example","lastName1","Applicant"+sequence,"birthDate","2000-01-01","gender","FEMALE","emails",List.of(unique("follow-up")),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog "+sequence,"sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000003"+String.format("%06d",sequence)),"planId",plan,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1"))));
    }
    ObjectNode withEmail(ObjectNode body,String email) { ((ObjectNode)body.get("person")).set("emails",mapper.valueToTree(List.of(email)));return body; }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception {return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.header("Host",host).with(jwt().jwt(j->j.subject("follow-up-admin").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request,String id) {
        String account=member(id).getString("accountId");return request.header("Host",host).with(jwt().jwt(j->j.subject(account).claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString().isEmpty()?"{}":response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    /** Each submission comes from its own fictional address: the R-04-20 limits are per club and IP. */
    JsonNode submit(Object body) throws Exception {
        String ip="203.0.113."+(++sequence%250+1);
        return result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr(ip);return r;}),201);
    }
    Document member(String id) {return mongo.getCollection("members").find(new Document("_id",id).append("clubId",club)).first();}
    List<Document> collection(String name) {return mongo.getCollection(name).find(new Document("clubId",club)).into(new ArrayList<>());}
    String pendingDog(String id) {return collection("dogs").stream().filter(d->id.equals(d.getString("memberId"))&&"PENDING".equals(d.getString("status"))).findFirst().orElseThrow().getString("_id");}
    JsonNode review(String id) throws Exception { return result(admin(get("/api/v1/members/"+id+"/signup")),200); }
    Map<String,Object> validation(String id,String planId) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("version",review(id).path("version").asLong());
        body.put("dogs",List.of(Map.of("dogId",pendingDog(id),"levelId",level)));if(planId!=null) body.put("planId",planId);
        return body;
    }
    JsonNode dryRun(String id,String planId) throws Exception { return result(admin(postJson("/members/"+id+"/validation",validation(id,planId)).param("dryRun","true")),200); }
    JsonNode validate(String id) throws Exception {
        var body=validation(id,null);var dry=dryRun(id,null);
        if("PENDING".equals(member(id).getString("status"))) body.put("nextInvoiceDate",dry.path("nextInvoiceDate").asText());
        body.put("upfrontAmountPaid",mapper.convertValue(dry.at("/upfront/totalDue"),Map.class));
        return result(admin(postJson("/members/"+id+"/validation",body)),200);
    }
    JsonNode reject(String id) throws Exception { return result(admin(postJson("/members/"+id+"/rejection",Map.of("version",member(id).get("version"),"reason","Fictional rejection reason"))),200); }
    /** An active member who left in June 2025, with the dog made inactive when leaving (as in {@code SignupSecurityFixesIT}). */
    String leftMember(ObjectNode original) throws Exception {
        String id=submit(original).path("memberId").asText();validate(id);
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("status","LEFT").append("leftAt",Date.from(Instant.parse("2025-06-30T10:00:00Z")))
                .append("leftReason","LEAVE_REQUEST").append("leaveDate","2025-06-30")));
        mongo.getCollection("dogs").updateMany(new Document("memberId",id),new Document("$set",new Document("status","INACTIVE").append("deactivationReason","MEMBER_LEFT")));
        return id;
    }
    ObjectNode readmission(ObjectNode original,String email,String locale) {
        var body=withEmail(original.deepCopy(),email);body.put("locale",locale);
        ((ObjectNode)body.get("person")).put("firstName","Returning").put("lastName1","Applicant");
        return body;
    }
    List<Document> events(String type) { return collection("domain_events").stream().filter(e -> type.equals(e.getString("type"))).toList(); }
    void dispatch() { for(int i=0;i<12;i++) dispatcher.dispatch(); }
    com.agilityhub.core.clubs.messaging.application.FakeEmailSender mailbox() { return (com.agilityhub.core.clubs.messaging.application.FakeEmailSender)sender; }
    List<com.agilityhub.core.clubs.messaging.application.EmailMessage> mailsTo(String email) { return mailbox().messages().stream().filter(m -> email.equals(m.to())).toList(); }
    void parameter(String key,Object value) {
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is(club).and("key").is(key)),Parameter.class);
        mongo.insert(new Parameter(UUID.randomUUID().toString(),club,key,value,"unknown","club",null,List.of(),0L,clock.instant()));configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    void stripe() {
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("paymentProviders",new Document("MANUAL",new Document("enabled",true)).append("SEPA_XML",new Document("enabled",true)).append("STRIPE",new Document("enabled",true)))));
        configs.invalidate(club);signupService.invalidateConfiguration(club);
    }

    // ---- Step 1 (review #1, R-04-06 c, R-04-26): the checkout of a pending readmission ----
    @Test void R_04_06_R_04_26_T_04_22_aPendingReadmissionsCheckoutGoesToTheApplicantsSubmittedAddress() throws Exception {
        String recorded=unique("recorded"),applicant=unique("applicant");
        var original=withEmail(request(),recorded);String id=leftMember(original);String account=member(id).getString("accountId");
        stripe();
        var readmit=readmission(original,applicant,"es");readmit.set("payment",mapper.valueToTree(Map.of("type","CARD","firstMonthOption","TODAY")));
        var submitted=submit(readmit);assertThat(submitted.path("memberId").asText()).isEqualTo(id);assertThat(submitted.at("/checkout/required").asBoolean()).isTrue();
        var checkout=Map.of("memberId",id,"signupToken",submitted.path("signupToken").asText(),"successUrl","https://"+host+"/ok","cancelUrl","https://"+host+"/cancel");
        String session=result(postJson("/checkout-sessions",checkout).header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr("203.0.113.251");return r;}),201).path("checkoutSessionId").asText();
        var request=fake.request(session);
        assertThat(request.customerEmail()).as("the provider's customer is the applicant").isEqualTo(applicant);
        assertThat(request.lines()).isNotEmpty();
        // Neither the LEFT record nor the account's login email changes before validation.
        assertThat(member(id).getList("contactEmails",Document.class).getFirst().getString("email")).isEqualTo(recorded);
        assertThat(mongo.getCollection("accounts").find(new Document("_id",account)).first().getString("email")).isEqualTo(recorded);
    }

    // ---- Step 2 (review #2, R-04-20): the recipient cap is admitted once per event and notification ----
    static String consumer(String bean) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bean.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    @Test void R_04_20_aDeliveryRetriedAfterItsAdmissionReusesItAndSends() throws Exception {
        parameter("signup.rateLimit",Map.of("identityChecksPerHour",10,"familyGroupLookupsPerHour",20,"uploadUrlsPerHour",30,"signupPerHour",5,"signupPerDay",20,
                "checkoutSessionsAnonymousPerHour",10,"townsPerHour",60,"notificationsPerRecipientPerHour",1));
        String email=unique("retried");mailbox().failNextTo(email);
        String id=submit(withEmail(request(),email)).path("memberId").asText();
        dispatch();
        // The first attempt was admitted, then the provider failed: nothing sent, the consumer not processed, a retry pending.
        var event=events("SignupSubmitted").stream().filter(e -> id.equals(e.get("payload",Document.class).getString("memberId"))).findFirst().orElseThrow();
        assertThat(mailsTo(email)).isEmpty();
        assertThat(event.getString("status")).isEqualTo("PENDING");assertThat(event.getInteger("attempts")).isEqualTo(1);
        assertThat(event.get("processedAt",Document.class)).doesNotContainKey(consumer("signupSubmittedMail"));
        assertThat(collection("notifications").stream().filter(n -> "N-01".equals(n.getString("code"))&&email.equals(n.getString("recipientEmail"))).map(n -> n.getString("status"))).containsExactly("QUEUED");
        clock.advance(Duration.ofSeconds(2));dispatch();
        // The retry reuses its admission: the mail leaves, and only then is the consumer marked processed.
        assertThat(mailsTo(email)).hasSize(1);
        var retried=mongo.getCollection("domain_events").find(new Document("_id",event.get("_id"))).first();
        assertThat(retried.getString("status")).isEqualTo("PUBLISHED");assertThat(retried.get("processedAt",Document.class)).containsKey(consumer("signupSubmittedMail"));
        assertThat(collection("notifications").stream().filter(n -> "N-01".equals(n.getString("code"))&&email.equals(n.getString("recipientEmail"))).map(n -> n.getString("status"))).containsExactly("SENT");
        // Only new events count: the next N-01 to the same address within the hour is capped.
        validate(id);dispatch();
        result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Capped Dog","sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000004"+String.format("%06d",++sequence)),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID()),id),201);
        dispatch();
        assertThat(collection("notifications").stream().filter(n -> "N-01".equals(n.getString("code"))&&email.equals(n.getString("recipientEmail")))).as("one N-01 an hour").hasSize(1);
        assertThat(collection("domain_events").stream().filter(e -> !"PUBLISHED".equals(e.getString("status")))).isEmpty();
    }

    // ---- Round 2, point 1 (review #1 of E3-T12, R-04-20): the admission survives a restart ----
    Map<String,Object> cap(int perRecipient) {
        return Map.of("identityChecksPerHour",10,"familyGroupLookupsPerHour",20,"uploadUrlsPerHour",30,"signupPerHour",5,"signupPerDay",20,
                "checkoutSessionsAnonymousPerHour",10,"townsPerHour",60,"notificationsPerRecipientPerHour",perRecipient);
    }
    /** The id of the only {@code SignupSubmitted} event of a member that is not among {@code known}. */
    String newSubmitted(String memberId,String... known) {
        var ids=events("SignupSubmitted").stream().filter(e -> memberId.equals(e.get("payload",Document.class).getString("memberId")))
                .map(e -> e.getString("_id")).filter(e -> !List.of(known).contains(e)).toList();
        assertThat(ids).hasSize(1);return ids.getFirst();
    }
    Document byId(String collection,String id) { return mongo.getCollection(collection).find(new Document("_id",id)).first(); }
    String addDog(String memberId,String name) throws Exception {
        return result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name",name,"sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000007"+String.format("%06d",++sequence)),"documents",List.of()))
                .header("Idempotency-Key",UUID.randomUUID()),memberId),201).path("dogId").asText();
    }
    @Test void R_04_20_anAdmittedEventKeepsItsAdmissionAcrossARestartAndNewEventsAreStillCounted() throws Exception {
        parameter("signup.rateLimit",cap(1));
        String email=unique("restart");mailbox().failNextTo(email);
        String id=submit(withEmail(request(),email)).path("memberId").asText();
        dispatch();
        // A is admitted, then its delivery fails: nothing sent, a retry pending.
        String a=newSubmitted(id);Instant processed;
        assertThat(mailsTo(email)).isEmpty();assertThat(byId("domain_events",a).getString("status")).isEqualTo("PENDING");
        // The application restarts: the per-instance buckets start empty.
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(notifications);
        Object before=org.springframework.test.util.ReflectionTestUtils.getField(target,"limits");
        org.springframework.test.util.ReflectionTestUtils.setField(target,"limits",new com.agilityhub.core.shared.application.RateLimits(true,Map.of(),clock));
        String b,c;
        try {
            // B, a new event to the same address, takes the only allowance of the fresh bucket before A's retry is due.
            validate(id);addDog(id,"Second Dog");dispatch();
            b=newSubmitted(id,a);
            assertThat(byId("notifications",b+":applicant").getString("status")).isEqualTo("SENT");
            assertThat(byId("notifications",a+":applicant").getString("status")).as("A not retried yet").isEqualTo("QUEUED");
            // A's retry keeps its stored admission and sends; only then is its consumer processed.
            clock.advance(Duration.ofSeconds(2));dispatch();processed=clock.instant();
            assertThat(byId("notifications",a+":applicant").getString("status")).isEqualTo("SENT");
            assertThat(byId("domain_events",a).getString("status")).isEqualTo("PUBLISHED");
            assertThat(byId("domain_events",a).get("processedAt",Document.class)).containsKey(consumer("signupSubmittedMail"));
            // A third event is still counted: capped on the fresh bucket.
            addDog(id,"Third Dog");dispatch();
            c=newSubmitted(id,a,b);
            assertThat(byId("notifications",c+":applicant")).as("C capped").isNull();
            assertThat(mailsTo(email).stream().filter(m -> m.text().contains("Second Dog")||m.text().contains("Third Dog"))).as("B's N-01 only").hasSize(1);
            assertThat(collection("domain_events").stream().filter(e -> !"PUBLISHED".equals(e.getString("status")))).isEmpty();
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(target,"limits",before); }
        // The decisions are stored per event and notification, the refusal too, with the club and without the address.
        var stored=byId("signup_notification_admissions",a+":N-01");
        assertThat(stored).containsEntry("clubId",club).containsEntry("eventId",a).containsEntry("notificationCode","N-01").containsEntry("admitted",true);
        // E3-T15: its retention is its event's, from the moment the event is processed (`jobs.retention.domainEventsDays`, 90).
        assertThat(stored.getDate("expiresAt").toInstant()).isEqualTo(processed.plus(Duration.ofDays(90)));
        assertThat(stored.toJson()).doesNotContain(email);
        assertThat(collection("signup_notification_admissions").stream().map(d -> d.getString("_id")+"="+d.getBoolean("admitted")))
                .containsExactlyInAnyOrder(a+":N-01=true",b+":N-01=true",c+":N-01=false");
        var ttl=mongo.getCollection("signup_notification_admissions").listIndexes().into(new ArrayList<>()).stream().filter(i -> "signup_admission_ttl".equals(i.getString("name"))).findFirst().orElseThrow();
        assertThat(ttl.get("key",Document.class)).isEqualTo(new Document("expiresAt",1));assertThat(ttl.get("expireAfterSeconds",Number.class).intValue()).isZero();
        // Two deliveries of one event racing for the first decision: the first stored one wins.
        String race=UUID.randomUUID().toString();
        try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(club)) {
            var first=admissions.decide(new com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission(race+":N-01",club,race,"N-01",true,clock.instant(),null));
            var second=admissions.decide(new com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission(race+":N-01",club,race,"N-01",false,clock.instant(),null));
            assertThat(first.admission().admitted()).isTrue();assertThat(second.admission().admitted()).isTrue();
            // E3-T15: only the delivery that stored the decision charges the allowance.
            assertThat(first.stored()).isTrue();assertThat(second.stored()).isFalse();
            assertThat(admissions.decision(race,"N-01")).hasValueSatisfying(d -> assertThat(d.admitted()).isTrue());
        }
    }

    // ---- Step 3 (review #3, R-04-06 c, S04 §8): N-01 and N-03 take their values from the event ----
    @Test void R_04_06_T_04_12_T_04_19_queuedReadmissionsKeepTheirOwnLocaleDogPlanAndTotal() throws Exception {
        String recorded=unique("recorded"),first=unique("first"),second=unique("second");
        var original=withEmail(request(),recorded);String id=leftMember(original);dispatch();mailbox().clear();
        // Readmission A (Spanish, TODAY on 01-01: 60 € + the 100 € entry) is rejected; readmission B (English, ALTERNATIVE: half
        // a month, 30 € + 100 €) reuses the same dog under another name. Nothing is dispatched in between.
        var a=readmission(original,first,"es");((ObjectNode)a.get("dog")).put("name","Alpha Dog");submit(a);reject(id);
        clock.advance(Duration.ofSeconds(60));
        var b=readmission(original,second,"en");((ObjectNode)b.get("dog")).put("name","Beta Dog");((ObjectNode)b.get("payment")).put("firstMonthOption","ALTERNATIVE");
        submit(b);
        assertThat(collection("dogs").stream().filter(d -> id.equals(d.getString("memberId")))).as("B reused A's dog").hasSize(1);
        dispatch();
        var toFirst=mailsTo(first);
        assertThat(toFirst).as("A's N-01 and N-03").hasSize(2).allSatisfy(m -> assertThat(m.locale().getLanguage()).isEqualTo("es"));
        var n01=toFirst.stream().filter(m -> m.text().contains("Alpha Dog")).toList();
        assertThat(n01).as("A's N-01 names A's dog").hasSize(1);
        assertThat(n01.getFirst().text()).contains(new Money(16000,"EUR").format(Locale.forLanguageTag("es"))).doesNotContain("Beta Dog");
        assertThat(toFirst.stream().filter(m -> m.text().contains("Fictional rejection reason"))).as("A's N-03").hasSize(1);
        var toSecond=mailsTo(second);
        assertThat(toSecond).as("B's N-01").singleElement().satisfies(m -> {
            assertThat(m.locale().getLanguage()).isEqualTo("en");
            assertThat(m.text()).contains("Beta Dog",new Money(13000,"EUR").format(Locale.forLanguageTag("en"))).doesNotContain("Alpha Dog");
        });
        // The values travel in the payloads, frozen at the commit of each submission and rejection.
        var submittedEvents=events("SignupSubmitted").stream().map(e -> e.get("payload",Document.class)).filter(p -> id.equals(p.getString("memberId"))&&Boolean.TRUE.equals(p.getBoolean("readmission"))).toList();
        assertThat(submittedEvents).extracting(p -> p.getString("locale")).containsExactly("es","en");
        assertThat(submittedEvents).extracting(p -> p.getList("dogNames",String.class)).containsExactly(List.of("Alpha Dog"),List.of("Beta Dog"));
        assertThat(submittedEvents).extracting(p -> p.get("upfrontTotal",Document.class).get("amountMinor",Number.class).longValue()).containsExactly(16000L,13000L);
        assertThat(events("SignupRejected")).singleElement().satisfies(e -> assertThat(e.get("payload",Document.class).getString("locale")).isEqualTo("es"));
    }
    /** The N-01 of an add-dog and the N-03 of its rejection read the event too: a later rename of the dog changes neither. */
    @Test void R_04_23_T_04_21_anAddDogN01AndN03KeepTheirValuesWhenTheDogChangesBeforeDispatch() throws Exception {
        String id=submit(request()).path("memberId").asText();validate(id);dispatch();mailbox().clear();
        String email=member(id).getList("contactEmails",Document.class).getFirst().getString("email");
        String dog=result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Queued Dog","sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000005"+String.format("%06d",++sequence)),"documents",List.of()))
                .header("Idempotency-Key",UUID.randomUUID()).header("Accept-Language","es"),id),201).path("dogId").asText();
        reject(id);
        // Before the outbox runs, the rejected dog's own block and name change (a later edit, as a later submission would).
        mongo.getCollection("dogs").updateOne(new Document("_id",dog),new Document("$set",new Document("name","Renamed Dog").append("signup.locale","en")));
        dispatch();
        var mails=mailsTo(email);
        assertThat(mails).hasSize(2).allSatisfy(m -> assertThat(m.locale().getLanguage()).isEqualTo("es"));
        assertThat(mails.stream().filter(m -> m.text().contains("Queued Dog"))).as("N-01 names the submitted dog").hasSize(1);
        assertThat(mails).noneMatch(m -> m.text().contains("Renamed Dog"));
    }

    /** Events queued before the payloads carried these values (no `locale`) still render from the submission block. */
    @Test void R_04_23_T_04_21_eventsQueuedBeforeThePayloadCarriedTheValuesReadTheSubmissionBlock() throws Exception {
        var body=request();body.put("locale","es");String email=body.at("/person/emails/0").asText();String dogName=body.at("/dog/name").asText();
        String id=submit(body).path("memberId").asText();
        var older=new Document("$unset",new Document("payload.locale","").append("payload.dogNames","").append("payload.upfrontTotal",""));
        mongo.getCollection("domain_events").updateMany(new Document("clubId",club).append("type","SignupSubmitted"),older);
        dispatch();
        assertThat(mailsTo(email)).singleElement().satisfies(m -> {
            assertThat(m.locale().getLanguage()).isEqualTo("es");
            assertThat(m.text()).contains(dogName,new Money(16000,"EUR").format(Locale.forLanguageTag("es")));
        });
        reject(id);mongo.getCollection("domain_events").updateMany(new Document("clubId",club).append("type","SignupRejected"),older);
        dispatch();
        assertThat(mailsTo(email)).hasSize(2).allSatisfy(m -> assertThat(m.locale().getLanguage()).isEqualTo("es"));
        assertThat(collection("domain_events").stream().filter(e -> !"PUBLISHED".equals(e.getString("status")))).isEmpty();
    }

    // ---- Step 5 (web E3-W07, R-04-15, S04 §3): the first month in the D2 view ----
    static void firstMonth(JsonNode value,String option,String portion,String startDate,long amountMinor) {
        assertThat(value.path("option").asText()).isEqualTo(option);assertThat(value.path("portion").asText()).isEqualTo(portion);
        assertThat(value.path("startDate").asText()).isEqualTo(startDate);
        assertThat(value.at("/amountDue/amountMinor").asLong()).isEqualTo(amountMinor);assertThat(value.at("/amountDue/currency").asText()).isEqualTo("EUR");
    }
    @Test void R_04_15_T_04_18_d2NamesTheFirstMonthFrozenAtSubmissionAndTheDryRunRecalculatesIt() throws Exception {
        // Day 5 (< split day 16): TODAY is a full month from the 5th.
        clock.setInstant(Instant.parse("2026-01-05T08:00:00Z"));String early=submit(request()).path("memberId").asText();
        firstMonth(review(early).at("/upfront/firstMonth"),"TODAY","FULL","2026-01-05",6000);
        firstMonth(dryRun(early,null).at("/upfront/firstMonth"),"TODAY","FULL","2026-01-05",6000);
        // Day 17 (≥ 16): TODAY is half a month from the 17th.
        clock.setInstant(Instant.parse("2026-01-17T08:00:00Z"));String late=submit(request()).path("memberId").asText();
        firstMonth(review(late).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        firstMonth(dryRun(late,null).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        // Frozen: a later split day changes neither the view nor the unchanged-plan dry run.
        parameter("signup.firstMonthSplitDay",20);
        firstMonth(review(late).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        firstMonth(dryRun(late,null).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        parameter("signup.firstMonthSplitDay",16);
        // A plan change recalculates it in the dry run (half of 80 €); the view keeps the frozen rows of the submission.
        String other=UUID.randomUUID().toString();plan("OTHER",other,8000);
        firstMonth(dryRun(late,other).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",4000);
        firstMonth(review(late).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        // A block frozen before the portion was stored gets it from its frozen amount (round 2: see below).
        olderSnapshot(late);
        firstMonth(review(late).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        // No FIRST_MONTH line, no first month: an added dog pays an additional-dog fee instead.
        validate(early);
        result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Added Dog","sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000006"+String.format("%06d",++sequence)),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID()),early),201);
        assertThat(review(early).at("/upfront").has("firstMonth")).isFalse();assertThat(dryRun(early,null).at("/upfront").has("firstMonth")).isFalse();
    }
    /** The submission's blocks as frozen before E3-T12: `signup.upfront.firstMonth` without its `portion`. */
    void olderSnapshot(String memberId) { frozen(memberId,new Document("$unset",new Document("signup.upfront.firstMonth.portion",""))); }
    void frozen(String memberId,Document update) {
        mongo.getCollection("members").updateMany(new Document("_id",memberId),update);mongo.getCollection("dogs").updateMany(new Document("memberId",memberId),update);
    }

    // ---- Round 2, point 2 (review #2 of E3-T12, R-04-15): an older snapshot keeps its historical portion ----
    @Test void R_04_15_T_04_18_anOlderSnapshotKeepsTheHalfMonthItChargedWhenTheSplitDayAndThePriceChange() throws Exception {
        // 17 January with the split day 16: TODAY is half a month, 30 € of the 60 € plan.
        clock.setInstant(Instant.parse("2026-01-17T08:00:00Z"));String id=submit(request()).path("memberId").asText();
        olderSnapshot(id);
        // A later split day (20), under which the 17th would be a full month, changes nothing.
        parameter("signup.firstMonthSplitDay",20);
        firstMonth(review(id).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        firstMonth(dryRun(id,null).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        // Nor does a later price: the monthly price in force on the submission day tells the portion, not today's (70 € from February).
        mongo.getCollection("prices").updateMany(new Document("clubId",club).append("planId",plan),new Document("$set",new Document("validTo","2026-01-31")));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,plan,PriceConcept.MONTHLY_FEE,new Money(7000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2026,2,1),null,0,clock.instant(),clock.instant(),null,null));
        signupService.invalidateConfiguration(club);clock.setInstant(Instant.parse("2026-02-10T08:00:00Z"));
        firstMonth(review(id).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        // An amount that is neither the full price nor its half cannot tell: the portion is null, never guessed.
        frozen(id,new Document("$set",new Document("signup.upfront.firstMonth.amountDue.amountMinor",4200)));
        var unknown=review(id).at("/upfront/firstMonth");
        assertThat(unknown.has("portion")).isTrue();assertThat(unknown.path("portion").isNull()).isTrue();
        assertThat(unknown.path("option").asText()).isEqualTo("TODAY");assertThat(unknown.at("/amountDue/amountMinor").asLong()).isEqualTo(4200);
    }

    // ---- Round 2, point 3 (review #3 of E3-T12, step 5): `firstMonth` only with a FIRST_MONTH line ----
    void billing(boolean enabled) {
        var modules=Arrays.stream(Module.values()).filter(m -> enabled||m!=Module.BILLING).map(Enum::name).toList();
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("modules",modules)));
        configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    @Test void R_04_15_T_04_18_anAnswerNamesAFirstMonthOnlyWhenItsOwnLinesChargeOne() throws Exception {
        // A monthly plan submitted on 5 January while BILLING is off: no rows, no frozen quote. Then BILLING is enabled.
        clock.setInstant(Instant.parse("2026-01-05T08:00:00Z"));billing(false);
        String id=submit(request()).path("memberId").asText();
        billing(true);
        var view=review(id).path("upfront");
        assertThat(view.path("lines")).isEmpty();assertThat(view.has("firstMonth")).as("the view").isFalse();
        // The unchanged-plan dry run keeps those empty rows: it names no first month.
        var unchanged=dryRun(id,null).path("upfront");
        assertThat(unchanged.path("lines")).isEmpty();assertThat(unchanged.at("/totalDue/amountMinor").asLong()).isZero();
        assertThat(unchanged.has("firstMonth")).as("the unchanged-plan dry run").isFalse();
        // A plan change writes new rows, a FIRST_MONTH one among them: that answer names the month it charges (80 € from the 5th).
        String other=UUID.randomUUID().toString();plan("OTHER",other,8000);
        var changed=dryRun(id,other).path("upfront");
        assertThat(changed.path("lines").findValuesAsText("concept")).contains("FIRST_MONTH");
        firstMonth(changed.path("firstMonth"),"TODAY","FULL","2026-01-05",8000);
    }

    // ---- E3-T15 (review of E3-T12 round 2, R-04-20): a failure-safe recipient-cap admission ----
    /**
     * Runs {@code body} while the first {@code failures} admission writes of this club fail, as a lost Mongo primary would
     * make them. Other clubs' events (the dispatcher serves them all) are untouched.
     */
    void withFailingAdmissionWrites(int failures,org.junit.jupiter.api.function.Executable body) throws Throwable {
        var remaining=new java.util.concurrent.atomic.AtomicInteger(failures);
        var failing=new com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmissionRepository(mongo) {
            @Override public com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission insert(com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission decision) {
                if(club.equals(decision.clubId())&&remaining.getAndDecrement()>0) throw new org.springframework.dao.DataAccessResourceFailureException("Fictional admission write failure");
                return super.insert(decision);
            }
        };
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(notifications);
        Object before=org.springframework.test.util.ReflectionTestUtils.getField(target,"admissions");
        org.springframework.test.util.ReflectionTestUtils.setField(target,"admissions",failing);
        try { body.execute(); } finally { org.springframework.test.util.ReflectionTestUtils.setField(target,"admissions",before); }
    }
    List<String> decisions() { return collection("signup_notification_admissions").stream().map(d -> d.getString("_id")+"="+d.getBoolean("admitted")).toList(); }
    // Step 1: the decision is written before the charge, and a retry never re-charges.
    @Test void R_04_20_aDecisionThatFailsToBeStoredChargesNothingAndItsRetryAdmitsAndSendsOnce() throws Throwable {
        parameter("signup.rateLimit",cap(1));
        String email=unique("unstored");
        withFailingAdmissionWrites(1,() -> {
            String id=submit(withEmail(request(),email)).path("memberId").asText();
            dispatch();
            // The write failed: no decision stored, nothing sent, a retry pending.
            String a=newSubmitted(id);
            assertThat(byId("signup_notification_admissions",a+":N-01")).isNull();
            assertThat(mailsTo(email)).isEmpty();assertThat(byId("domain_events",a).getString("status")).isEqualTo("PENDING");
            clock.advance(Duration.ofSeconds(2));dispatch();
            // The retry decides on an untouched bucket: admitted, sent exactly once, and no refusal recorded.
            assertThat(mailsTo(email)).hasSize(1);
            assertThat(byId("notifications",a+":applicant").getString("status")).isEqualTo("SENT");
            assertThat(decisions()).containsExactly(a+":N-01=true");
            assertThat(byId("domain_events",a).getString("status")).isEqualTo("PUBLISHED");
        });
    }
    @Test void R_04_20_aDecisionThatFailsToBeStoredIsChargedOnceSoTheNextEventStillHasItsShare() throws Throwable {
        parameter("signup.rateLimit",cap(2));
        String email=unique("charged-once");
        withFailingAdmissionWrites(1,() -> {
            String id=submit(withEmail(request(),email)).path("memberId").asText();
            dispatch();clock.advance(Duration.ofSeconds(2));dispatch();
            String a=newSubmitted(id);
            assertThat(byId("notifications",a+":applicant").getString("status")).isEqualTo("SENT");
            // With a cap of 2, A's failed write and its retry took one allowance: B, within the hour, takes the second; C is refused.
            validate(id);addDog(id,"Second Dog");dispatch();String b=newSubmitted(id,a);
            addDog(id,"Third Dog");dispatch();String c=newSubmitted(id,a,b);
            assertThat(byId("notifications",b+":applicant")).as("B admitted").isNotNull();
            assertThat(byId("notifications",b+":applicant").getString("status")).isEqualTo("SENT");
            assertThat(byId("notifications",c+":applicant")).as("C refused").isNull();
            assertThat(decisions()).containsExactlyInAnyOrder(a+":N-01=true",b+":N-01=true",c+":N-01=false");
        });
    }
    /** What Mongo's TTL monitor removes once the clock has reached {@code expiresAt} (it runs on the server's own time). */
    void ttlMonitor() {
        mongo.getCollection("signup_notification_admissions").deleteMany(new Document("clubId",club).append("expiresAt",new Document("$lte",Date.from(clock.instant()))));
    }
    // Step 2: the decision lives as long as its event; its retention starts once the event is processed.
    @Test void R_04_20_anAdmittedEventPendingForMoreThanADayKeepsItsDecisionAndSendsAfterACompetingEvent() throws Exception {
        parameter("signup.rateLimit",cap(1));
        String email=unique("pending");mailbox().failNextTo(email);
        String id=submit(withEmail(request(),email)).path("memberId").asText();
        dispatch();
        // A is admitted, then its delivery fails: nothing sent, a retry pending.
        String a=newSubmitted(id);
        var whilePending=byId("signup_notification_admissions",a+":N-01");
        assertThat(whilePending).containsEntry("admitted",true);
        assertThat(byId("notifications",a+":applicant").getString("status")).isEqualTo("QUEUED");
        // More than a day passes with A still pending (an outage, a dispatch backlog); the TTL monitor runs meanwhile.
        clock.advance(Duration.ofDays(1).plusHours(1));ttlMonitor();
        // The backlog: A's retry comes due after the next events of this address.
        mongo.getCollection("domain_events").updateOne(new Document("_id",a),new Document("$set",new Document("nextAttemptAt",Date.from(clock.instant().plus(Duration.ofMinutes(5))))));
        // B, a competing event to the same address, takes this hour's only allowance; C arrives while the cap is full and is refused.
        validate(id);addDog(id,"Competing Dog");dispatch();String b=newSubmitted(id,a);
        addDog(id,"Refused Dog");dispatch();String c=newSubmitted(id,a,b);
        assertThat(byId("notifications",b+":applicant").getString("status")).isEqualTo("SENT");
        assertThat(byId("notifications",c+":applicant")).as("C refused while the cap is full").isNull();
        assertThat(byId("notifications",a+":applicant").getString("status")).as("A not retried yet").isEqualTo("QUEUED");
        // A's retry still sends: its decision outlived the day.
        clock.advance(Duration.ofMinutes(5));dispatch();Instant processed=clock.instant();
        assertThat(byId("notifications",a+":applicant").getString("status")).isEqualTo("SENT");
        assertThat(mailsTo(email).stream().filter(m -> m.text().contains("Refused Dog"))).isEmpty();
        assertThat(byId("domain_events",a).getString("status")).isEqualTo("PUBLISHED");
        assertThat(decisions()).containsExactlyInAnyOrder(a+":N-01=true",b+":N-01=true",c+":N-01=false");
        assertThat(collection("domain_events").stream().filter(e -> !"PUBLISHED".equals(e.getString("status")))).isEmpty();
        // No expiry while pending; once processed, the event's own retention (`jobs.retention.domainEventsDays`, 90 by default).
        assertThat(whilePending.get("expiresAt")).as("no expiry while pending").isNull();
        assertThat(byId("signup_notification_admissions",a+":N-01").getDate("expiresAt").toInstant()).isEqualTo(processed.plus(Duration.ofDays(90)));
        parameter("jobs.retention.domainEventsDays",7);
        addDog(id,"Short Retention Dog");clock.advance(Duration.ofHours(1));dispatch();String d=newSubmitted(id,a,b,c);
        assertThat(byId("signup_notification_admissions",d+":N-01").getDate("expiresAt").toInstant()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
    }

    // ---- E3-T16 step 4 (E3-T15 review #1): the «never charge twice» guard and the decision lock, through the service ----
    /** Swaps the service's admission repository for {@code repository} while {@code body} runs. */
    void withAdmissions(com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmissionRepository repository,org.junit.jupiter.api.function.Executable body) throws Throwable {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(notifications);
        Object before=org.springframework.test.util.ReflectionTestUtils.getField(target,"admissions");
        org.springframework.test.util.ReflectionTestUtils.setField(target,"admissions",repository);
        try { body.execute(); } finally { org.springframework.test.util.ReflectionTestUtils.setField(target,"admissions",before); }
    }
    /**
     * A delivery that stores its decision and is then told the key was taken (`DuplicateKeyException`): what a delivery sees
     * when another delivery of the same event, claimed again after the lease, stored the decision first. That one charged.
     */
    @Test void R_04_20_aDecisionAnotherDeliveryStoredFirstIsUsedAndNeverChargedAgain() throws Throwable {
        parameter("signup.rateLimit",cap(1));
        String email=unique("raced");
        var remaining=new java.util.concurrent.atomic.AtomicInteger(1);
        var racing=new com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmissionRepository(mongo) {
            @Override public com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission insert(com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission decision) {
                var stored=super.insert(decision);
                if(club.equals(decision.clubId())&&remaining.getAndDecrement()>0) throw new org.springframework.dao.DuplicateKeyException("Fictional decision stored first by another delivery");
                return stored;
            }
        };
        withAdmissions(racing,() -> {
            String id=submit(withEmail(request(),email)).path("memberId").asText();
            dispatch();
            // A uses the stored decision (admitted) and sends; the allowance is untouched, because this delivery did not store it.
            String a=newSubmitted(id);
            assertThat(byId("notifications",a+":applicant").getString("status")).isEqualTo("SENT");
            assertThat(decisions()).containsExactly(a+":N-01=true");
            // With a cap of 1, B, the next event to the same address within the hour, is still admitted.
            validate(id);addDog(id,"Second Dog");dispatch();String b=newSubmitted(id,a);
            assertThat(byId("notifications",b+":applicant")).as("B admitted: A charged nothing").isNotNull();
            assertThat(byId("notifications",b+":applicant").getString("status")).isEqualTo("SENT");
            // B charged the allowance: C is refused.
            addDog(id,"Third Dog");dispatch();String c=newSubmitted(id,a,b);
            assertThat(byId("notifications",c+":applicant")).as("C refused").isNull();
            assertThat(decisions()).containsExactlyInAnyOrder(a+":N-01=true",b+":N-01=true",c+":N-01=false");
        });
    }
    /**
     * The decision lock: while one event's decision is being stored (its allowance probed, not yet charged), a second event of
     * the same recipient waits, and then sees the charged bucket. Without the lock, both would take the cap's last allowance.
     */
    @Test void R_04_20_oneRecipientCapDecisionAtATimeSoTwoEventsNeverShareTheLastAllowance() throws Throwable {
        parameter("signup.rateLimit",cap(1));
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString(),hash="fictional-recipient-hash";
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var holding=new com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmissionRepository(mongo) {
            @Override public com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission insert(com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission decision) {
                if(first.equals(decision.eventId())) {
                    entered.countDown();
                    try { if(!release.await(30,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("never released"); }
                    catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); }
                }
                return super.insert(decision);
            }
        };
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(notifications);
        var lock=(java.util.concurrent.locks.ReentrantLock)org.springframework.test.util.ReflectionTestUtils.getField(target,"deciding");
        var threads=java.util.concurrent.Executors.newFixedThreadPool(2);
        withAdmissions(holding,() -> {
            try {
                java.util.function.Function<String,java.util.concurrent.Callable<com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission>> decide=event -> () -> {
                    try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(club)) {
                        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"decide",club,event,"N-01",hash);
                    }
                };
                var a=threads.submit(decide.apply(first));
                assertThat(entered.await(30,java.util.concurrent.TimeUnit.SECONDS)).as("A is storing its decision").isTrue();
                var b=threads.submit(decide.apply(second));
                // B either waits for the lock (the rule) or, without it, decides at once on the uncharged bucket.
                long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
                while(!b.isDone()&&!lock.hasQueuedThreads()&&System.nanoTime()<deadline) Thread.sleep(5);
                boolean waited=lock.hasQueuedThreads()&&!b.isDone();
                release.countDown();
                assertThat(a.get(30,java.util.concurrent.TimeUnit.SECONDS).admitted()).as("A admitted").isTrue();
                assertThat(waited).as("B waited for A's decision").isTrue();
                assertThat(b.get(30,java.util.concurrent.TimeUnit.SECONDS).admitted()).as("B refused: A took the only allowance").isFalse();
            } finally { release.countDown();threads.shutdownNow(); }
        });
        assertThat(decisions()).containsExactlyInAnyOrder(first+":N-01=true",second+":N-01=false");
    }
}
