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
    String club,host,plan,level;
    int sequence;
    @BeforeEach void fixtureClub() {
        club="fu-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();level=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of(),"SEPA_XML",Map.of())));
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
        // A block frozen before the portion was stored gets it from its option and start date.
        for(String collection:List.of("members","dogs")) mongo.getCollection(collection).updateMany(new Document(collection.equals("members")?"_id":"memberId",late),new Document("$unset",new Document("signup.upfront.firstMonth.portion","")));
        firstMonth(review(late).at("/upfront/firstMonth"),"TODAY","HALF","2026-01-17",3000);
        // No FIRST_MONTH line, no first month: an added dog pays an additional-dog fee instead.
        validate(early);
        result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Added Dog","sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000006"+String.format("%06d",++sequence)),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID()),early),201);
        assertThat(review(early).at("/upfront").has("firstMonth")).isFalse();assertThat(dryRun(early,null).at("/upfront").has("firstMonth")).isFalse();
    }
}
