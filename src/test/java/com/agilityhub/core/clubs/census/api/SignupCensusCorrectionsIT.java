package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.clubs.census.domain.CensusRules;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.ConcurrencySupport;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.agilityhub.core.payments.application.PaymentProvider;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E5-T28: the census and signup corrections the global audit of 26-09 pulled forward (steps 3, 4, 6 and 7): the SEPA
 * mandate of a new member (E43, INC-30), a rejection while a checkout is open (A3-01, INC-32) and the checkout's retried
 * transaction (A3-06), the ACTIVE dog's chip (A2-08, INC-35), and D1 and the members list on a pending readmission
 * (A3-02, INC-33). Round 2: the provider double and the idempotency store are spies, to hold the provider while a
 * rejection commits (review #2) and to lose the checkout's stored answer once (review #3).
 */
@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SignupCensusCorrectionsIT extends AbstractIntegrationTest {
    static final String IBAN="ES9121000418450200051332";
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signupService;
    @MockitoSpyBean com.agilityhub.core.payments.application.FakeCheckoutGateway fake;
    @MockitoSpyBean com.agilityhub.core.shared.persistence.IdempotencyRepository records;
    @Autowired com.agilityhub.core.shared.application.TransactionRetries retries;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    String club,host,plan,level;
    int sequence;
    @BeforeEach void fixtureClub() {
        // A 40-character slug (the longest a club may have): the mandate reference must still fit pain.008's 35 characters.
        club="fix-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();level=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of("enabled",true),"SEPA_XML",Map.of("enabled",true))));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        var text=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(plan,club,"MONTHLY",text,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,text,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,plan,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        // A real Level (D1's risk review reads the level catalog).
        mongo.insert(new Level(level,club,"A",new LocalizedText(Map.of("ca","Iniciació","es","Iniciación","en","Beginner"),"en"),0,"#112233",5,false,true,true,0,clock.instant(),clock.instant(),"admin","admin"));
    }
    String national() { int value=15000000+(++sequence);return String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23); }
    ObjectNode request() {
        String national=national();
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national),"firstName","Example","lastName1","Applicant"+sequence,"birthDate","2000-01-01","gender","FEMALE","emails",List.of("corrections"+sequence+"@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog "+sequence,"sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000005"+String.format("%06d",sequence)),"planId",plan,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1"))));
    }
    ObjectNode sepa(ObjectNode body,String iban) {
        var payment=new LinkedHashMap<String,Object>(Map.of("type","SEPA_DD","firstMonthOption","TODAY"));if(iban!=null) payment.put("iban",iban);
        body.set("payment",mapper.valueToTree(payment));return body;
    }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception {return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request,String ip) { return request.with(r->{r.setRemoteAddr(ip);return r;}); }
    String ip() { return "198.51.100."+(++sequence%250+1); }
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.with(jwt().jwt(j->j.subject("corrections-admin").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString().isEmpty()?"{}":response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    JsonNode submit(Object body,int status) throws Exception { return result(from(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),ip()),status); }
    JsonNode submit(Object body) throws Exception { return submit(body,201); }
    Document member(String id) {return mongo.getCollection("members").find(new Document("_id",id).append("clubId",club)).first();}
    Document dogOf(String memberId) { return mongo.getCollection("dogs").find(new Document("memberId",memberId).append("clubId",club)).first(); }
    List<Document> collection(String name) { return mongo.getCollection(name).find(new Document("clubId",club)).into(new ArrayList<>()); }
    JsonNode review(String id) throws Exception { return result(admin(get("/api/v1/members/"+id+"/signup").header("Host",host)),200); }
    JsonNode validate(String id) throws Exception {
        var review=review(id);var body=new LinkedHashMap<String,Object>();body.put("version",review.path("version").asLong());
        var dogs=new ArrayList<Map<String,Object>>();for(var dog:review.path("dogs")) dogs.add(Map.of("dogId",dog.path("id").asText(),"levelId",level));body.put("dogs",dogs);
        if(!review.at("/proposals/nextInvoiceDate").isMissingNode()&&"PENDING".equals(member(id).getString("status"))) body.put("nextInvoiceDate",review.at("/proposals/nextInvoiceDate").asText());
        var dry=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        body.put("upfrontAmountPaid",mapper.convertValue(dry.at("/upfront/totalDue"),Map.class));
        return result(admin(postJson("/members/"+id+"/validation",body)),200);
    }
    JsonNode reject(String id) throws Exception {return result(admin(postJson("/members/"+id+"/rejection",Map.of("version",member(id).get("version"),"reason","Fictional rejection reason"))),200);}
    /** The member leaves (its number, its mandate and its dog, INACTIVE, stay on the record). */
    void leave(String id) {
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("status","LEFT").append("leftAt",Date.from(clock.instant().minus(Duration.ofDays(90))))
                .append("leftReason","LEAVE_REQUEST").append("leaveDate","2025-06-30")));
        mongo.getCollection("dogs").updateMany(new Document("memberId",id),new Document("$set",new Document("status","INACTIVE").append("deactivationReason","MEMBER_LEFT")));
    }
    void stripe() {
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("paymentProviders",new Document("MANUAL",new Document("enabled",true))
                .append("SEPA_XML",new Document("enabled",true)).append("STRIPE",new Document("enabled",true)))));
        configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    Map<String,Object> checkout(JsonNode submitted) {
        return Map.of("memberId",submitted.path("memberId").asText(),"signupToken",submitted.path("signupToken").asText(),"successUrl","https://"+host+"/success","cancelUrl","https://"+host+"/cancel");
    }
    Document session(String id) { return mongo.getCollection("checkout_sessions").find(new Document("_id",id)).first(); }

    // ---- Step 3 (E43, INC-30): the mandate reference of a new SEPA member ----
    @Test void T_04_17_T_04_12_validationWritesTheMigratedMandateFormatAndAReadmissionSignsTheNextOne() throws Exception {
        var original=sepa(request(),IBAN);var submitted=submit(original);String id=submitted.path("memberId").asText();
        var signedAt=member(id).get("paymentMethod",Document.class).get("mandateSignedAt");
        assertThat(signedAt).isEqualTo(member(id).get("signup",Document.class).get("submittedAt"));
        clock.setInstant(clock.instant().plus(Duration.ofHours(2)));
        int number=validate(id).path("number").asInt();
        var payment=member(id).get("paymentMethod",Document.class);
        assertThat(payment.getString("mandateRef")).isEqualTo(CensusRules.mandateRef(club,number,null)).endsWith("-"+number+"-1").hasSizeLessThanOrEqualTo(35)
                .startsWith(club.substring(0,20));
        // S04 R-04-10 wins over S12 §7: the mandate is signed when the signup was submitted, not when it was validated.
        assertThat(payment.get("mandateSignedAt")).isEqualTo(signedAt);
        // A readmission signs a new mandate with a new IBAN: the same member number, the next sequence (never the same reference).
        leave(id);
        var readmission=sepa(original.deepCopy(),"ES5500000000000000000001");
        ((ObjectNode)readmission.get("person")).set("emails",mapper.valueToTree(List.of("readmitted"+sequence+"@example.test")));
        assertThat(submit(readmission).path("memberId").asText()).isEqualTo(id);
        validate(id);
        var renewed=member(id).get("paymentMethod",Document.class);
        assertThat(renewed.getString("iban")).isEqualTo("ES5500000000000000000001");
        assertThat(renewed.getString("mandateRef")).isEqualTo(CensusRules.mandateRef(club,number,payment.getString("mandateRef"))).endsWith("-"+number+"-2").hasSizeLessThanOrEqualTo(35);
    }

    // ---- Step 4 (A3-01, INC-32): a rejection while a checkout is open ----
    @Test void T_04_19_T_04_22_aRejectionExpiresTheOpenCheckoutAndALateProviderCompletionOnlyLeavesTheRefundMark() throws Exception {
        stripe();var body=request();body.set("payment",mapper.valueToTree(Map.of("type","CARD","firstMonthOption","TODAY")));
        var submitted=submit(body);String id=submitted.path("memberId").asText();
        String sid=result(from(postJson("/checkout-sessions",checkout(submitted)).header("Idempotency-Key",UUID.randomUUID()),ip()),201).path("checkoutSessionId").asText();
        assertThat(collection("upfront_payments")).isNotEmpty().allMatch(p->"CHECKOUT_PENDING".equals(p.getString("status")));
        var rejected=reject(id);
        assertThat(rejected.path("paidPaymentRequiresRefund").asBoolean()).isFalse();
        // The session expires on our side with its rows, and the provider is asked to expire it after the commit.
        assertThat(session(sid).getString("status")).isEqualTo("EXPIRED");
        assertThat(collection("upfront_payments")).allMatch(p->"CANCELLED".equals(p.getString("status")));
        assertThat(fake.expired(sid)).as("gateway.expire(%s)",sid).isTrue();
        // The provider completes anyway (E34): a WARN and the reconciliation mark, no PAID row, no card on the LEFT record.
        fake.complete(sid);fake.complete(sid);
        var late=session(sid);
        assertThat(late.getString("status")).isEqualTo("EXPIRED");assertThat(late.get("lateCompletionAt")).isNotNull();
        assertThat(late.getString("providerPaymentId")).isEqualTo("fake_payment_"+sid);
        assertThat(collection("upfront_payments")).allMatch(p->"CANCELLED".equals(p.getString("status")));
        assertThat(member(id).getString("status")).isEqualTo("LEFT");
        assertThat(member(id).get("paymentMethod",Document.class).get("card")).isNull();
        assertThat(collection("domain_events").stream().map(e->e.getString("type"))).doesNotContain("UpfrontPaymentSucceeded");
    }
    /** A signup session still PENDING whose rows are no longer CHECKOUT_PENDING (a rejection written before this fix). */
    @Test void T_04_22_aCompletionOfASessionWhoseRowsWereClosedTakesTheLatePath() throws Exception {
        stripe();var body=request();body.set("payment",mapper.valueToTree(Map.of("type","CARD","firstMonthOption","TODAY")));
        var submitted=submit(body);String id=submitted.path("memberId").asText();
        String sid=result(from(postJson("/checkout-sessions",checkout(submitted)).header("Idempotency-Key",UUID.randomUUID()),ip()),201).path("checkoutSessionId").asText();
        mongo.getCollection("upfront_payments").updateMany(new Document("clubId",club).append("memberId",id),new Document("$set",new Document("status","CANCELLED")));
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("status","LEFT").append("leftReason","SIGNUP_REJECTED")));
        fake.complete(sid);
        var late=session(sid);
        assertThat(late.getString("status")).isEqualTo("EXPIRED");assertThat(late.get("lateCompletionAt")).isNotNull();
        assertThat(late.getString("providerPaymentId")).isEqualTo("fake_payment_"+sid);
        assertThat(collection("upfront_payments")).allMatch(p->"CANCELLED".equals(p.getString("status")));
        assertThat(member(id).get("paymentMethod",Document.class).get("card")).isNull();
    }
    /**
     * Round 2 (review #2): the rejection commits after the checkout's first transaction and before the provider has its session,
     * so the rejection's own expiry cannot reach the provider. The checkout finds its session EXPIRED once the provider answers:
     * it expires the provider's session and answers 409 INVALID_STATE, never a checkout URL for the rejected signup.
     */
    @Test void T_04_19_T_04_22_aRejectionWhileTheProviderOpensTheSessionGetsNoCheckoutUrl() throws Exception {
        stripe();var body=request();body.set("payment",mapper.valueToTree(Map.of("type","CARD","firstMonthOption","TODAY")));
        var submitted=submit(body);String id=submitted.path("memberId").asText();
        var opening=new java.util.concurrent.atomic.AtomicReference<String>();
        try(var pool=Executors.newSingleThreadExecutor()) {
            doAnswer(invocation -> {
                opening.set(invocation.<PaymentProvider.Request>getArgument(0).sessionId());
                pool.submit(() -> reject(id)).get(60,TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(fake).createCheckoutSession(any());
            var refused=result(from(postJson("/checkout-sessions",checkout(submitted)).header("Idempotency-Key",UUID.randomUUID()),ip()),409);
            assertThat(refused.path("code").asText()).isEqualTo("INVALID_STATE");
            assertThat(refused.toString()).doesNotContain("checkoutUrl","checkout.test");
        }
        String sid=opening.get();
        assertThat(member(id).getString("status")).isEqualTo("LEFT");
        assertThat(session(sid).getString("status")).isEqualTo("EXPIRED");
        assertThat(collection("upfront_payments")).isNotEmpty().allMatch(p->"CANCELLED".equals(p.getString("status")));
        // The rejection's expiry came before the provider's session existed; the checkout expired it once it did.
        verify(fake,atLeast(2)).expire(sid);
        assertThat(fake.expired(sid)).as("gateway.expire(%s) reached the provider's session",sid).isTrue();
        // A completion that still arrives is a late one (E34): the mark, no PAID row, no card on the LEFT record.
        fake.complete(sid);
        assertThat(session(sid).get("lateCompletionAt")).isNotNull();
        assertThat(collection("upfront_payments")).allMatch(p->"CANCELLED".equals(p.getString("status")));
        assertThat(member(id).get("paymentMethod",Document.class).get("card")).isNull();
    }
    /**
     * Round 2 (review #3, CONVENCIONS_API §7): the provider opened the session but its 201 could not be stored. The key is
     * released; the retry with the same key finds the open session, asks the provider again for that same session, and answers
     * the same checkout. No second session opens, and the stored answer is then replayed without the provider.
     */
    @Test void T_04_22_T_04_23_aCheckoutWhoseAnswerWasLostIsAnsweredAgainWithTheSameSession() throws Exception {
        stripe();var body=request();body.set("payment",mapper.valueToTree(Map.of("type","CARD","firstMonthOption","TODAY")));
        var submitted=submit(body);var request=checkout(submitted);String key=UUID.randomUUID().toString(),ip=ip();
        doThrow(new IllegalStateException("checkout answer not stored (injected)")).doCallRealMethod().when(records).complete(any(),eq(201),any(),any());
        var lost=mvc.perform(from(postJson("/checkout-sessions",request).header("Idempotency-Key",key),ip)).andReturn().getResponse();
        assertThat(lost.getStatus()).as(lost.getContentAsString()).isEqualTo(500);
        var opened=collection("checkout_sessions");
        assertThat(opened).singleElement().satisfies(s -> assertThat(s.getString("status")).isEqualTo("PENDING"));
        String sid=opened.getFirst().getString("_id");
        assertThat(collection("upfront_payments")).isNotEmpty().allMatch(p->"CHECKOUT_PENDING".equals(p.getString("status")));
        var again=result(from(postJson("/checkout-sessions",request).header("Idempotency-Key",key),ip),201);
        assertThat(again.path("checkoutSessionId").asText()).isEqualTo(sid);
        assertThat(again.path("checkoutUrl").asText()).isEqualTo("https://checkout.test/"+sid);
        assertThat(collection("checkout_sessions")).hasSize(1);
        var asked=ArgumentCaptor.forClass(PaymentProvider.Request.class);
        verify(fake,times(2)).createCheckoutSession(asked.capture());
        assertThat(asked.getAllValues()).extracting(PaymentProvider.Request::sessionId).containsOnly(sid);
        assertThat(asked.getAllValues().get(1)).isEqualTo(asked.getAllValues().get(0));
        // The answer is stored now: the same key replays it, and the provider is not asked again.
        assertThat(result(from(postJson("/checkout-sessions",request).header("Idempotency-Key",key),ip),201)).isEqualTo(again);
        verify(fake,times(2)).createCheckoutSession(any());
        // Another key cannot open a second checkout of the same rows while this one is open.
        assertThat(result(from(postJson("/checkout-sessions",request).header("Idempotency-Key",UUID.randomUUID()),ip),409).path("code").asText()).isEqualTo("INVALID_STATE");
    }
    /** A3-06 (R-04-27): the checkout meets a concurrent census write, retries in the signup's transaction, and answers 201. */
    @Test void T_04_23_aCheckoutMeetingAConcurrentCensusWriteIsRetriedNeverA500() throws Exception {
        stripe();var body=request();body.set("payment",mapper.valueToTree(Map.of("type","CARD","firstMonthOption","TODAY")));
        var submitted=submit(body);var request=checkout(submitted);String key=UUID.randomUUID().toString(),ip=ip();
        var transactions=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        double before=retries.retries("signup");
        org.springframework.mock.web.MockHttpServletResponse answer;
        try(var held=new ConcurrencySupport.HeldTransaction(transactions,club,()->mongo.upsert(Query.query(Criteria.where("_id").is(club+":census").and("clubId").is(club)),
                new Update().inc("sequence",1),"census_write_locks"));var pool=Executors.newSingleThreadExecutor()) {
            var response=pool.submit(()->mvc.perform(from(postJson("/checkout-sessions",request).header("Idempotency-Key",key),ip)).andReturn().getResponse());
            // Until the checkout met the held write and retried (or answered: before E5-T28 it gave a 500 at once).
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(retries.retries("signup")<=before&&!response.isDone()&&System.nanoTime()<deadline) Thread.sleep(2);
            held.commit();
            answer=response.get(60,TimeUnit.SECONDS);
        }
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(201);
        assertThat(retries.retries("signup")).as("the checkout met the held census write and ran again").isGreaterThan(before);
        String sid=mapper.readTree(answer.getContentAsString()).path("checkoutSessionId").asText();
        assertThat(session(sid).getString("status")).isEqualTo("PENDING");assertThat(fake.request(sid)).isNotNull();
        // The 201 was stored with its key: a retry of the same request replays it and opens no second session.
        var replay=result(from(postJson("/checkout-sessions",request).header("Idempotency-Key",key),ip),201);
        assertThat(replay.path("checkoutSessionId").asText()).isEqualTo(sid);
        assertThat(mongo.getCollection("checkout_sessions").countDocuments(new Document("clubId",club))).isEqualTo(1);
    }

    // ---- Step 6 (A2-08, INC-35): the chip of an ACTIVE dog ----
    @Test void T_03_32_T_04_12_anActiveDogsChipIsStoredNormalisedAndTheIndexCatchesALaterSignup() throws Exception {
        String id=submit(request()).path("memberId").asText();validate(id);
        var dog=dogOf(id);String dogId=dog.getString("_id");
        result(admin(patch("/api/v1/dogs/"+dogId).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",dog.get("version"),"chip","ABC")))),400);
        result(admin(patch("/api/v1/dogs/"+dogId).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",dog.get("version"),"chip","941 000 005-999 999")))),200);
        assertThat(dogOf(id).getString("chip")).isEqualTo("941000005999999");
        var later=request();((ObjectNode)later.get("dog")).put("chip","941000005999999");
        assertThat(submit(later,422).path("code").asText()).isEqualTo("DOG_CHIP_ALREADY_REGISTERED");
    }

    // ---- Step 7 (A3-02, INC-33): D1 and the members list read a pending readmission's submitted values ----
    @Test void T_04_12_T_04_14_D1AndTheMembersListReadAPendingReadmissionsSubmittedValues() throws Exception {
        var original=request();String id=submit(original).path("memberId").asText();validate(id);leave(id);
        var readmission=sepa(original.deepCopy(),null);
        ((ObjectNode)readmission.get("person")).put("firstName","Returning").put("lastName1","Readmitted");
        ((ObjectNode)readmission.at("/consents/imageUse")).put("granted",true);
        ((ObjectNode)readmission.get("dog")).put("name","Renamed Dog");
        assertThat(submit(readmission).path("memberId").asText()).isEqualTo(id);
        // The LEFT record keeps its own values (E38); D2 judges the submission.
        assertThat(member(id).getString("firstName")).isEqualTo("Example");assertThat(member(id).get("paymentMethod",Document.class).getString("type")).isEqualTo("MANUAL");
        assertThat(dogOf(id).getString("name")).startsWith("Example Dog");
        assertThat(review(id).path("warnings").toString()).contains("ACCOUNT_NOT_PROVIDED","READMISSION").doesNotContain("NO_IMAGE_CONSENT");
        // D1: the row follows the submission, as D2.
        JsonNode row=null;
        for(var item:result(admin(get("/api/v1/dashboard")),200).at("/pendingSignups/items")) if(id.equals(item.path("memberId").asText())) row=item;
        assertThat(row).as("D1 row").isNotNull();
        assertThat(row.path("shortName").asText()).isEqualTo("Returning R.");
        assertThat(row.path("paymentMethodType").asText()).isEqualTo("SEPA_DD");
        assertThat(row.path("warnings").toString()).contains("ACCOUNT_NOT_PROVIDED","READMISSION").doesNotContain("NO_IMAGE_CONSENT");
        assertThat(row.at("/dogs/0/name").asText()).isEqualTo("Renamed Dog");
        // The members list (D5) too.
        var items=result(admin(get("/api/v1/members").param("filter","id:eq:"+id)),200).path("items");
        assertThat(items).hasSize(1);var listed=items.get(0);
        assertThat(listed.path("fullName").asText()).startsWith("Returning Readmitted");
        assertThat(listed.at("/paymentMethod/type").asText()).isEqualTo("SEPA_DD");assertThat(listed.at("/paymentMethod/channel").isMissingNode()||listed.at("/paymentMethod/channel").isNull()).isTrue();
        assertThat(listed.path("warnings").toString()).contains("ACCOUNT_NOT_PROVIDED","READMISSION").doesNotContain("NO_IMAGE_CONSENT");
        assertThat(listed.at("/imageRights/granted").asBoolean()).isTrue();
        assertThat(listed.at("/dogs/0/name").asText()).isEqualTo("Renamed Dog");assertThat(listed.at("/pendingDogs/0/name").asText()).isEqualTo("Renamed Dog");
        // Another member's row is untouched by the readmission projection.
        String other=submit(request()).path("memberId").asText();
        var plain=result(admin(get("/api/v1/members").param("filter","id:eq:"+other)),200).path("items").get(0);
        assertThat(plain.path("fullName").asText()).startsWith("Example Applicant");assertThat(plain.path("warnings").toString()).contains("NO_IMAGE_CONSENT");
    }
}
