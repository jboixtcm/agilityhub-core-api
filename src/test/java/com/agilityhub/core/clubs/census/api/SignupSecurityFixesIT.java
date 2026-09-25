package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.agilityhub.core.support.ConcurrencySupport;
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
 * E3-T09: the security and privacy fixes of the gate E3 audit (`roadmap/reviews/gate-E3/consolidated.md`, M16–M18 and
 * «Api, security») and the fresh signup configuration of step 5. Same Spring context as {@link SignupIT}.
 */
@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SignupSecurityFixesIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signupService;
    @Autowired com.agilityhub.core.shared.application.OutboxDispatcher dispatcher;
    @Autowired com.agilityhub.core.clubs.messaging.application.EmailSender sender;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;
    @Autowired com.agilityhub.core.shared.application.SignupCapabilities capabilities;
    @Autowired com.agilityhub.core.shared.application.TransactionRetries retries;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired com.agilityhub.core.clubs.census.persistence.CensusRepository<com.agilityhub.core.clubs.census.persistence.Dog> dogs;
    String club,host,plan,level;
    int sequence;
    static final String IBAN="ES5500000000000000000001";
    @BeforeEach void fixtureClub() { club=newClub("sec");host=club+".example.test"; }
    String newClub(String prefix) {
        String id=prefix+"-"+UUID.randomUUID();String clubHost=id+".example.test";plan=UUID.randomUUID().toString();level=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(id,clubHost));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of(),"SEPA_XML",Map.of())));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(id);hosts.invalidate();
        var text=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(plan,id,"MONTHLY",text,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,text,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),id,plan,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Document("_id",level).append("clubId",id).append("active",true).append("name",Map.of("en","Beginner")).append("order",0),"levels");
        return id;
    }
    String national() { int value=14000000+(++sequence);return String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23); }
    ObjectNode request() {
        String national=national();
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national),"firstName","Example","lastName1","Applicant"+sequence,"birthDate","2000-01-01","gender","FEMALE","emails",List.of("security"+sequence+"@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog "+sequence,"sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000002"+String.format("%06d",sequence)),"planId",plan,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1"))));
    }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception {return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request,String ip) { return request.with(r->{r.setRemoteAddr(ip);return r;}); }
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.with(jwt().jwt(j->j.subject("security-admin").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request,String id) {
        String account=member(id).getString("accountId");return request.header("Host",host).with(jwt().jwt(j->j.subject(account).claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString().isEmpty()?"{}":response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    JsonNode submit(Object body,int status) throws Exception {
        String ip="198.51.100."+(++sequence%250+1);
        return result(from(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),ip),status);
    }
    JsonNode submit(Object body) throws Exception { return submit(body,201); }
    Document member(String id) {return mongo.getCollection("members").find(new Document("_id",id).append("clubId",club)).first();}
    List<Document> collection(String name) {return mongo.getCollection(name).find(new Document("clubId",club)).into(new ArrayList<>());}
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
    String activeMember(ObjectNode body) throws Exception { String id=submit(body).path("memberId").asText();validate(id);return id; }
    void parameter(String key,Object value) {
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is(club).and("key").is(key)),Parameter.class);
        mongo.insert(new Parameter(UUID.randomUUID().toString(),club,key,value,"unknown","club",null,List.of(),0L,clock.instant()));configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    void dispatch() { for(int i=0;i<12;i++) dispatcher.dispatch(); }
    private String administrator() {
        String id=UUID.randomUUID().toString();
        mongo.insert(new com.agilityhub.core.identity.persistence.Account(id,id+"@example.test","Example Admin","en",null,Set.of(),com.agilityhub.core.identity.persistence.Account.Status.ACTIVE,null,Map.of(),false,clock.instant()));
        mongo.insert(new com.agilityhub.core.identity.persistence.Membership(id,id,club,null,Set.of(com.agilityhub.core.identity.domain.Role.ADMIN),com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE,com.agilityhub.core.identity.domain.Role.ADMIN));
        return id;
    }
    Map<String,Object> identity(ObjectNode body) { return Map.of("idDocument",body.at("/person/idDocument"),"emails",List.of("someone-else@example.test")); }

    // ---- Step 1 (M16): the signed headers of the signup upload ----
    @Test void R_04_08_T_04_13_signupUploadUrlReturnsTheHeadersTheStorageSigned() throws Exception {
        var upload=result(from(postJson("/signup/upload-urls",Map.of("fileName","vaccination.pdf","contentType","application/pdf","sizeBytes",10)),"198.51.100.201"),200);
        assertThat(upload.path("headers").path("Content-Type").asText()).isEqualTo("application/pdf");
        assertThat(upload.path("headers").path("If-None-Match").asText()).isEqualTo("*");
        // The add-dog flow uses the same route as a MEMBER.
        String id=activeMember(request());
        var own=result(asMember(postJson("/signup/upload-urls",Map.of("fileName","card.jpg","contentType","image/jpeg","sizeBytes",10)),id),200);
        assertThat(own.path("headers").path("If-None-Match").asText()).isEqualTo("*");
    }

    // ---- Step 2 (M17): the limiter matches the path Spring routes ----
    @Test void R_04_20_T_04_28_percentEncodedAndDoubleSlashRoutesShareTheLimitOfThePlainRoute() throws Exception {
        var body=identity(request());String ip="198.51.100.202";
        for(int i=0;i<10;i++) result(from(postJson("/signup/identity-checks",body),ip),200);
        var encoded=post(java.net.URI.create("/api/v1/signup/identity-%63hecks")).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));
        assertThat(result(from(encoded,ip),429).path("code").asText()).isEqualTo("RATE_LIMITED");
        var doubled=post(java.net.URI.create("/api/v1/signup//identity-checks")).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));
        // Spring Security's StrictHttpFirewall rejects `//` before any filter; the limiter would match it too.
        assertThat(mvc.perform(from(doubled,ip)).andReturn().getResponse().getStatus()).as("a // variant never reaches the controller").isEqualTo(400);
        // Another client of the same club keeps its own bucket.
        result(from(postJson("/signup/identity-checks",body),"198.51.100.203"),200);
    }

    // ---- Step 3 (M18, E38): a readmission does not overwrite the LEFT record ----
    /** An active member who left in June 2025 (reason LEAVE_REQUEST), with the dog made inactive when leaving. */
    String leftMember(ObjectNode original) throws Exception {
        String id=activeMember(original);
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("status","LEFT").append("leftAt",Date.from(Instant.parse("2025-06-30T10:00:00Z")))
                .append("leftReason","LEAVE_REQUEST").append("leaveDate","2025-06-30").append("memberNumber",214)));
        mongo.getCollection("dogs").updateMany(new Document("memberId",id),new Document("$set",new Document("status","INACTIVE").append("deactivationReason","MEMBER_LEFT")));
        return id;
    }
    ObjectNode readmission(ObjectNode original) {
        var body=original.deepCopy();var person=(ObjectNode)body.get("person");
        person.set("emails",mapper.valueToTree(List.of("readmitted"+sequence+"@example.test")));
        person.set("phones",mapper.valueToTree(List.of(Map.of("prefix","+34","number","600000099"))));
        person.set("address",mapper.valueToTree(Map.of("street","New example street","postalCode","99999","town","Example town")));
        body.set("payment",mapper.valueToTree(Map.of("type","SEPA_DD","iban",IBAN,"firstMonthOption","TODAY")));
        ((ObjectNode)body.at("/consents/imageUse")).put("granted",true);
        return body;
    }
    static final List<String> PERSON=List.of("idDocument","firstName","lastName1","lastName2","gender","birthDate","contactEmails","phones","address","paymentMethod","consents","memberNumber","accountId","leftAt","leftReason","leaveDate");
    @AuditCovers(AuditAction.SIGNUP_SUBMITTED)
    @Test void T_04_12_readmissionKeepsTheLeftRecordUntilValidationWhichAppliesTheSubmittedValues() throws Exception {
        var original=request();String id=leftMember(original);var before=member(id);long consents=before.getList("consents",Document.class).size();
        assertThat(submit(readmission(original)).path("memberId").asText()).isEqualTo(id);
        var pending=member(id);
        for(String field:PERSON) assertThat(pending.get(field)).as(field).isEqualTo(before.get(field));
        assertThat(pending.getString("status")).isEqualTo("PENDING");assertThat(pending.get("signup",Document.class).getBoolean("readmission")).isTrue();
        // The submission is audited, masked, with the public origin.
        var submitted=collection("audit_entries").stream().filter(e->"SIGNUP_SUBMITTED".equals(e.getString("action"))&&id.equals(e.getString("entityId"))).toList();
        assertThat(submitted).hasSize(1);assertThat(submitted.getFirst().getString("origin")).isEqualTo("PUBLIC");
        assertThat(submitted.getFirst().toJson()).doesNotContain(IBAN).doesNotContain(original.at("/person/idDocument/value").asText()).contains("0001");
        // D2 shows the record and the request side by side; never the full IBAN.
        var review=review(id);
        assertThat(review.at("/readmission/current/phones/0/number").asText()).isEqualTo("600000001");
        assertThat(review.at("/readmission/submitted/phones/0/number").asText()).isEqualTo("600000099");
        assertThat(review.at("/readmission/submitted/contactEmails/0/email").asText()).startsWith("readmitted");
        assertThat(review.at("/readmission/submitted/paymentMethod/type").asText()).isEqualTo("SEPA_DD");
        assertThat(review.at("/readmission/current/paymentMethod/type").asText()).isEqualTo("MANUAL");
        assertThat(mapper.convertValue(review.at("/readmission/changedFields"),List.class)).contains("contactEmails","phones","address","paymentMethod").doesNotContain("firstName");
        assertThat(review.at("/readmission/previousLeftReason").asText()).isEqualTo("LEAVE_REQUEST");
        assertThat(review.toString()).doesNotContain(IBAN);assertThat(review.path("warnings").toString()).contains("READMISSION").doesNotContain("ACCOUNT_NOT_PROVIDED");
        // Validation applies the request, audited with the masked diff.
        validate(id);var validated=member(id);
        assertThat(validated.getString("status")).isEqualTo("ACTIVE");assertThat(validated.getInteger("memberNumber")).isEqualTo(214);
        assertThat(validated.getList("phones",Document.class).getFirst().getString("number")).isEqualTo("600000099");
        assertThat(validated.getList("contactEmails",Document.class).getFirst().getString("email")).startsWith("readmitted");
        assertThat(validated.get("address",Document.class).getString("street")).isEqualTo("New example street");
        assertThat(validated.get("paymentMethod",Document.class).getString("iban")).isEqualTo(IBAN);
        var ledger=validated.getList("consents",Document.class);assertThat(ledger).hasSize((int)consents+2);assertThat(ledger.getLast().getBoolean("granted")).isTrue();
        assertThat(validated.get("readmissionRequest")).isNull();assertThat(validated.get("leftAt")).isNull();
        var audit=collection("audit_entries").stream().filter(e->"MEMBER_VALIDATED".equals(e.getString("action"))&&id.equals(e.getString("entityId"))).reduce((a,b)->b).orElseThrow();
        assertThat(audit.toJson()).contains("600000099","···· ···· ···· ···· 0001").doesNotContain(IBAN);
    }
    @Test void T_04_12_T_04_19_rejectedReadmissionLeavesTheRecordExactlyAsItWas() throws Exception {
        var original=request();String id=leftMember(original);var before=member(id);
        submit(readmission(original));
        // Round 2 (R-04-06 b): the readmission matched on the document, so D2 cannot change it while it is pending.
        long version=((Number)member(id).get("version")).longValue();
        var locked=result(admin(patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",version,
                "idDocument",Map.of("type","DNI","number",national()))))),409);
        assertThat(locked.path("code").asText()).isEqualTo("INVALID_STATE");assertThat(locked.at("/details/reason").asText()).isEqualTo("READMISSION_PENDING");
        // Sending the document it already has is no edit.
        result(admin(patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",version,
                "idDocument",Map.of("type","DNI","number",original.at("/person/idDocument/value").asText()))))),200);
        reject(id);
        var after=member(id);
        var expected=new Document(before);expected.remove("version");expected.remove("updatedAt");
        var actual=new Document(after);actual.remove("version");actual.remove("updatedAt");
        assertThat(actual).isEqualTo(expected);
        assertThat(after.getString("status")).isEqualTo("LEFT");assertThat(after.getString("leftReason")).isEqualTo("LEAVE_REQUEST");
        assertThat(after.getDate("leftAt").toInstant()).isEqualTo(Instant.parse("2025-06-30T10:00:00Z"));
        // The next attempt is a readmission again (R-04-23).
        submit(readmission(original));assertThat(member(id).get("signup",Document.class).getBoolean("readmission")).isTrue();
    }

    @Test void T_04_12_T_04_20_aD2EditOfAPendingReadmissionEditsTheSubmittedValuesNotTheRecord() throws Exception {
        var original=request();String id=leftMember(original);var before=member(id);
        submit(readmission(original));long version=((Number)member(id).get("version")).longValue();
        result(admin(patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",version,
                "phones",List.of(Map.of("prefix","+34","number","600000077")),"paymentMethod",Map.of("type","SEPA_DD","sepa",Map.of("iban",IBAN,"holderName","Edited Holder")))))),200);
        var pending=member(id);
        for(String field:PERSON) assertThat(pending.get(field)).as(field).isEqualTo(before.get(field));
        var review=review(id);
        assertThat(review.at("/readmission/submitted/phones/0/number").asText()).isEqualTo("600000077");
        assertThat(review.at("/readmission/submitted/paymentMethod/holderName").asText()).isEqualTo("Edited Holder");
        assertThat(review.at("/readmission/current/phones/0/number").asText()).isEqualTo("600000001");
        // The event and the audit carry the edit masked.
        var edited=collection("domain_events").stream().filter(e->"SignupEdited".equals(e.getString("type"))).toList();
        assertThat(edited).hasSize(1);assertThat(edited.getFirst().toJson()).contains("600000077").doesNotContain(IBAN);
        assertThat(collection("audit_entries").stream().filter(e->"SIGNUP_EDITED".equals(e.getString("action"))&&id.equals(e.getString("entityId"))).map(Document::toJson).toList())
                .singleElement().satisfies(json->assertThat(json).contains("600000077").doesNotContain(IBAN));
        validate(id);
        assertThat(member(id).getList("phones",Document.class).getFirst().getString("number")).isEqualTo("600000077");
        assertThat(member(id).get("paymentMethod",Document.class).getString("holderName")).isEqualTo("Edited Holder");
    }

    // ---- Round 2: the pending readmission (S04 R-04-06 a–d, organizer 24-09) and the family lookup's name key ----
    static String unique(String prefix) { return prefix+"-"+UUID.randomUUID()+"@example.test"; }
    ObjectNode withEmail(ObjectNode body,String email) { ((ObjectNode)body.get("person")).set("emails",mapper.valueToTree(List.of(email)));return body; }

    /** Point 1 (R-04-05 + R-04-06 a): the primary address a readmission submitted is matched as for any pending member. */
    @Test void R_04_05_R_04_06_aPendingReadmissionsSubmittedAddressIsMatchedLikeAnyPendingMember() throws Exception {
        var original=request();String id=leftMember(original);
        var readmit=readmission(original);String address=readmit.at("/person/emails/0").asText();submit(readmit);
        var check=Map.of("idDocument",Map.of("type","DNI","value",national()),"emails",List.of(address));
        assertThat(result(from(postJson("/signup/identity-checks",check),"198.51.100.240"),200).path("result").asText()).isEqualTo("SIGNUP_ALREADY_PENDING");
        int members=collection("members").size();
        assertThat(submit(withEmail(request(),address),422).path("code").asText()).isEqualTo("SIGNUP_ALREADY_PENDING");
        assertThat(collection("members")).as("never a second pending application").hasSize(members);
        // The record's own primary address still matches, as before.
        var old=Map.of("idDocument",Map.of("type","DNI","value",national()),"emails",List.of(original.at("/person/emails/0").asText()));
        assertThat(result(from(postJson("/signup/identity-checks",old),"198.51.100.240"),200).path("result").asText()).isEqualTo("SIGNUP_ALREADY_PENDING");
        assertThat(member(id).getString("status")).isEqualTo("PENDING");
    }

    /** Point 3 (S04 §8 APPLICANT, R-04-06 c): N-01 and N-03 of a readmission reach the submitted address only. */
    @Test void R_04_06_T_04_12_aReadmissionsN01AndN03GoToTheSubmittedApplicantOnly() throws Exception {
        var mailbox=(com.agilityhub.core.clubs.messaging.application.FakeEmailSender)sender;
        String recorded=unique("recorded"),applicant=unique("applicant");String admin=administrator();
        var original=withEmail(request(),recorded);String oldSurname=original.at("/person/lastName1").asText();
        String id=leftMember(original);dispatch();mailbox.clear();
        var readmit=withEmail(readmission(original),applicant);readmit.put("locale","es");((ObjectNode)readmit.get("person")).put("firstName","Returning").put("lastName1","Readmitted");
        submit(readmit);dispatch();
        var received=mailbox.messages().stream().filter(m->m.to().equals(applicant)).toList();
        assertThat(received).as("N-01 to the submitted address").hasSize(1);assertThat(received.getFirst().locale().getLanguage()).isEqualTo("es");
        assertThat(collection("notifications").stream().filter(n->"N-01".equals(n.getString("code"))&&applicant.equals(n.getString("recipientEmail"))))
                .singleElement().satisfies(n->assertThat(n.getString("locale")).isEqualTo("es"));
        // The admins' copy names who applied: the submitted name, nothing from the LEFT record.
        var adminCopy=mailbox.messages().stream().filter(m->m.to().equals(admin+"@example.test")).toList();
        assertThat(adminCopy).hasSize(1);assertThat(adminCopy.getFirst().text()).contains("Returning Readmitted").doesNotContain(oldSurname);
        assertThat(mailbox.messages().stream().filter(m->m.to().equals(recorded))).as("nothing to the LEFT record's address").isEmpty();
        mailbox.clear();reject(id);
        // The rejection restored the record (its old address); the recipient travelled in the event.
        assertThat(member(id).getList("contactEmails",Document.class).getFirst().getString("email")).isEqualTo(recorded);
        var rejected=collection("domain_events").stream().filter(e->"SignupRejected".equals(e.getString("type"))).toList();
        assertThat(rejected).singleElement().satisfies(e->assertThat(e.toJson()).contains(applicant).doesNotContain(recorded));
        dispatch();
        var n03=mailbox.messages().stream().filter(m->m.to().equals(applicant)).toList();
        assertThat(n03).as("N-03 to the submitted address").hasSize(1);assertThat(n03.getFirst().locale().getLanguage()).isEqualTo("es");
        assertThat(n03.getFirst().text()).contains("Returning").doesNotContain(oldSurname);
        assertThat(mailbox.messages().stream().filter(m->m.to().equals(recorded))).as("nothing to the LEFT record's address").isEmpty();
    }

    /** Point 4 (R-04-12): the lookup compares a normalised name key; «Example  Dog» is found by «Example Dog». */
    @Test void R_04_12_familyLookupFindsAStoredDogNameWithRepeatedSpaces() throws Exception {
        var holder=request();((ObjectNode)holder.get("person")).put("firstName","Spaced").put("lastName1","Holder");((ObjectNode)holder.get("dog")).put("name","Example  Dog");
        submit(holder);
        assertThat(result(from(postJson("/signup/family-group-lookups",Map.of("holderName","Spaced Holder","dogName","Example Dog")),"198.51.100.241"),200).path("result").asText()).isEqualTo("FOUND");
        // A dog stored before the key existed is found once the startup migration has run; running it again changes nothing.
        String legacy=UUID.randomUUID().toString(),legacyDog=UUID.randomUUID().toString();
        mongo.getCollection("members").insertOne(new Document("_id",legacy).append("clubId",club).append("status","ACTIVE").append("firstName","Legacy").append("lastName1","Holder")
                .append("idDocument",new Document("type","DNI").append("number",national())).append("contactEmails",List.of(new Document("email",unique("legacy")))).append("version",0L));
        mongo.getCollection("dogs").insertOne(new Document("_id",legacyDog).append("clubId",club).append("memberId",legacy).append("name"," Legacy \t Dog ").append("status","ACTIVE").append("version",0L));
        var migration=context.getBean("dogNameKeyMigration",org.springframework.boot.ApplicationRunner.class);
        migration.run(new org.springframework.boot.DefaultApplicationArguments());
        var migrated=mongo.getCollection("dogs").find(new Document("_id",legacyDog)).first();
        assertThat(migrated.getString("nameKey")).isEqualTo("Legacy Dog");
        migration.run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(mongo.getCollection("dogs").find(new Document("_id",legacyDog)).first()).isEqualTo(migrated);
        assertThat(result(from(postJson("/signup/family-group-lookups",Map.of("holderName","Legacy Holder","dogName","legacy dog")),"198.51.100.241"),200).path("result").asText()).isEqualTo("FOUND");
        // A database indexed by the first round (`name_ci` on `name`) gets the key's index instead at the next start.
        mongo.getCollection("dogs").createIndex(new Document("clubId",1).append("name",1),new com.mongodb.client.model.IndexOptions().name("name_ci")
                .collation(com.mongodb.client.model.Collation.builder().locale("en").collationStrength(com.mongodb.client.model.CollationStrength.PRIMARY).build()));
        dogs.ensureNameIndex();
        assertThat(mongo.getCollection("dogs").listIndexes().into(new ArrayList<>()).stream().map(index->index.getString("name"))).contains("dog_name_key").doesNotContain("name_ci");
    }

    /** Point 5 (R-04-22, R-04-06 d): a readmission keeps the member's account and never changes its login email. */
    @Test void R_04_22_T_04_12_aValidatedReadmissionKeepsTheMembersAccountAndMembership() throws Exception {
        String login=unique("login"),submitted=unique("returning");
        var original=withEmail(request(),login);String id=leftMember(original);String account=member(id).getString("accountId");
        assertThat(account).isNotNull();
        submit(withEmail(readmission(original),submitted));validate(id);
        assertThat(member(id).getString("accountId")).isEqualTo(account);
        assertThat(member(id).getList("contactEmails",Document.class).getFirst().getString("email")).isEqualTo(submitted);
        assertThat(mongo.getCollection("accounts").countDocuments(new Document("email",submitted))).as("no second account").isZero();
        assertThat(mongo.getCollection("accounts").find(new Document("_id",account)).first().getString("email")).as("the login email never changes").isEqualTo(login);
        assertThat(mongo.getCollection("memberships").find(new Document("clubId",club).append("memberId",id)).into(new ArrayList<>())).singleElement()
                .satisfies(m->{assertThat(m.getString("accountId")).isEqualTo(account);assertThat(m.getString("status")).isEqualTo("ACTIVE");assertThat(m.getList("roles",String.class)).contains("MEMBER");});
    }

    /** Point 6 (R-04-20): the recipient cap is `signup.rateLimit.notificationsPerRecipientPerHour`, read per club. */
    @Test void R_04_05_R_04_20_theRecipientCapIsTheClubsParameter() throws Exception {
        parameter("signup.rateLimit",Map.of("identityChecksPerHour",10,"familyGroupLookupsPerHour",20,"uploadUrlsPerHour",30,"signupPerHour",5,"signupPerDay",20,
                "checkoutSessionsAnonymousPerHour",10,"townsPerHour",60,"notificationsPerRecipientPerHour",1));
        var body=withEmail(request(),unique("capped"));activeMember(body);dispatch();
        for(int i=0;i<3;i++) assertThat(result(from(postJson("/signup/identity-checks",identity(body)),"198.51.100."+(242+i)),200).path("result").asText()).isEqualTo("VERIFICATION_SENT");
        dispatch();
        assertThat(collection("notifications").stream().filter(n->"N-39".equals(n.getString("code")))).as("one N-39 an hour for this club").hasSize(1);
    }

    // ---- Step 4.1: signup.rateLimit is read per club ----
    @Test void R_04_20_signupRateLimitParameterIsReadPerClub() throws Exception {
        parameter("signup.rateLimit",Map.of("identityChecksPerHour",2,"familyGroupLookupsPerHour",20,"uploadUrlsPerHour",30,"signupPerHour",5,"signupPerDay",20,"checkoutSessionsAnonymousPerHour",10,"townsPerHour",60));
        var body=identity(request());String ip="198.51.100.204";
        result(from(postJson("/signup/identity-checks",body),ip),200);result(from(postJson("/signup/identity-checks",body),ip),200);
        result(from(postJson("/signup/identity-checks",body),ip),429);
        // Another club keeps the catalog default (10/hour) for the same address.
        club=newClub("so");host=club+".example.test";
        for(int i=0;i<3;i++) result(from(postJson("/signup/identity-checks",body),ip),200);
    }

    // ---- Step 4.2: per-recipient caps on N-39 and on the applicant's N-01 ----
    @Test void R_04_05_R_04_20_n39AndTheApplicantsN01AreCappedPerRecipient() throws Exception {
        var mailbox=(com.agilityhub.core.clubs.messaging.application.FakeEmailSender)sender;mailbox.clear();
        var body=request();String id=activeMember(body);dispatch();String email=body.at("/person/emails/0").asText();
        long welcome=mailbox.messages().stream().filter(m->m.to().equals(email)).count();
        for(int i=0;i<5;i++) assertThat(result(from(postJson("/signup/identity-checks",identity(body)),"198.51.100."+(210+i)),200).path("result").asText()).isEqualTo("VERIFICATION_SENT");
        dispatch();
        assertThat(mailbox.messages().stream().filter(m->m.to().equals(email)).count()-welcome).as("N-39 per recipient").isEqualTo(3);
        assertThat(collection("notifications").stream().filter(n->"N-39".equals(n.getString("code")))).hasSize(3);
        for(int i=0;i<5;i++) result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Capped Dog "+i,"sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000003"+String.format("%06d",++sequence)),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID()),id),201);
        dispatch();
        // The signup's own N-01 counts too: 1 + 2 of the 5 added dogs within the hour.
        assertThat(collection("notifications").stream().filter(n->"N-01".equals(n.getString("code"))&&email.equals(n.getString("recipientEmail")))).as("applicant N-01 per recipient and hour").hasSize(3);
        clock.advance(Duration.ofHours(1));
        result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Next Hour Dog","sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000003"+String.format("%06d",++sequence)),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID()),id),201);
        dispatch();
        assertThat(collection("notifications").stream().filter(n->"N-01".equals(n.getString("code"))&&email.equals(n.getString("recipientEmail")))).as("a new hour, a new allowance").hasSize(4);
    }

    // ---- Step 4.3: consents under impersonation record the actor ----
    @Test void R_04_17_T_04_21_consentsAcceptedUnderImpersonationRecordTheActor() throws Exception {
        String id=activeMember(request());String admin=administrator();
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("legal.legalTextsVersion","v2")));configs.invalidate(club);signupService.invalidateConfiguration(club);
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(club)) { issued=impersonations.create(admin,id,"Example support request"); }
        var consents=Map.of("privacyPolicy",Map.of("accepted",true,"version","v2"),"imageUse",Map.of("granted",true,"version","v2"));
        var call=postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Impersonated Dog","sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000004"+String.format("%06d",++sequence)),"documents",List.of(),"consents",consents))
                .header("Idempotency-Key",UUID.randomUUID()).with(jwt().jwt(issued.token()).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
        result(call,201);
        var ledger=member(id).getList("consents",Document.class);var added=ledger.subList(ledger.size()-2,ledger.size());
        assertThat(added).allSatisfy(entry->{assertThat(entry.getString("version")).isEqualTo("v2");assertThat(entry.getString("actorAccountId")).isEqualTo(admin);assertThat(entry.getString("origin")).isEqualTo("BACKOFFICE");});
        // The applicant's own public acceptance has no actor and the public origin.
        assertThat(ledger.getFirst().getString("origin")).isEqualTo("PUBLIC");assertThat(ledger.getFirst().get("actorAccountId")).isNull();
    }

    // ---- Step 4.4: the anonymous routes answer SIGNUP_CLOSED like POST /signup ----
    @Test void R_04_27_T_04_23_anonymousSignupRoutesAreClosedWithTheForm() throws Exception {
        var holder=request();((ObjectNode)holder.get("person")).put("firstName","Holder").put("lastName1","Example");activeMember(holder);
        byte[] bytes="fictional".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var upload=result(from(postJson("/signup/upload-urls",Map.of("fileName","card.pdf","contentType","application/pdf","sizeBytes",bytes.length)),"198.51.100.205"),200);
        for(boolean suspended:List.of(false,true)) {
            if(suspended) { parameter("signup.enabled",true);mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("status","SUSPENDED")));configs.invalidate(club); }
            else parameter("signup.enabled",false);
            String ip="198.51.100."+(suspended?207:206);
            assertThat(result(from(postJson("/signup/identity-checks",identity(request())),ip),422).path("code").asText()).isEqualTo("SIGNUP_CLOSED");
            assertThat(result(from(postJson("/signup/family-group-lookups",Map.of("holderName","Holder Example","dogName",holder.at("/dog/name").asText())),ip),422).path("code").asText()).isEqualTo("SIGNUP_CLOSED");
            assertThat(result(from(postJson("/signup/upload-urls",Map.of("fileName","card.pdf","contentType","application/pdf","sizeBytes",10)),ip),422).path("code").asText()).isEqualTo("SIGNUP_CLOSED");
            assertThat(result(from(put(java.net.URI.create(upload.path("uploadUrl").asText())).header("Host",host).contentType("application/pdf").content(bytes),ip),422).path("code").asText()).isEqualTo("SIGNUP_CLOSED");
            assertThat(result(from(postJson("/signup",request()).header("Idempotency-Key",UUID.randomUUID()),ip),422).path("code").asText()).isEqualTo("SIGNUP_CLOSED");
        }
    }

    // ---- Step 4.5: bounded inputs and indexed lookups ----
    @Test void R_04_20_lookupInputsAreBounded() throws Exception {
        String ip="198.51.100.208";
        assertThat(result(from(postJson("/signup/family-group-lookups",Map.of("holderName","H".repeat(121),"dogName","Dog")),ip),400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(result(from(postJson("/signup/family-group-lookups",Map.of("holderName","Holder Example","dogName","D".repeat(41))),ip),400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(result(from(postJson("/signup/identity-checks",Map.of("idDocument",Map.of("type","DNI","value","1".repeat(31)),"emails",List.of("a@example.test"))),ip),400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(result(from(postJson("/signup/identity-checks",Map.of("idDocument",Map.of("type","DNI","value",national()),"emails",List.of("a".repeat(250)+"@example.test"))),ip),400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        var claim=request();claim.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","H".repeat(121),"dogName","Dog","leavePending",true)));
        assertThat(submit(claim,400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
    @Test void R_04_05_R_04_12_identityAndFamilyLookupsUseIndexedQueries() throws Exception {
        for(int i=0;i<30;i++) {
            String memberId=UUID.randomUUID().toString();
            mongo.getCollection("members").insertOne(new Document("_id",memberId).append("clubId",club).append("status","ACTIVE").append("firstName","Bulk").append("lastName1","Member"+i)
                    .append("idDocument",new Document("type","DNI").append("number",national())).append("contactEmails",List.of(new Document("email","bulk"+i+"@example.test"))).append("version",0L));
            mongo.getCollection("dogs").insertOne(new Document("_id",UUID.randomUUID().toString()).append("clubId",club).append("memberId",memberId).append("name","Bulk Dog "+i).append("status","ACTIVE").append("version",0L));
        }
        var holder=request();((ObjectNode)holder.get("person")).put("firstName","Àngela").put("lastName1","Holder");String holderId=activeMember(holder);
        var database=mongo.getDb();database.getCollection("system.profile").drop();database.runCommand(new Document("profile",2));
        // The profiler stamps `ts` with the server's wall clock (the application's clock is the MockClock).
        Date since=Date.from(database.runCommand(new Document("hello",1)).getDate("localTime").toInstant().minusSeconds(1));
        try {
            assertThat(result(from(postJson("/signup/identity-checks",Map.of("idDocument",holder.at("/person/idDocument"),"emails",List.of("unknown@example.test"))),"198.51.100.209"),200).path("result").asText()).isEqualTo("VERIFICATION_SENT");
            assertThat(result(from(postJson("/signup/identity-checks",Map.of("idDocument",Map.of("type","DNI","value",national()),"emails",List.of(holder.at("/person/emails/0").asText()))),"198.51.100.209"),200).path("result").asText()).isEqualTo("VERIFICATION_SENT");
            // Round 2: a miss runs every match query, the pending readmissions' submitted addresses included (R-04-06 a).
            assertThat(result(from(postJson("/signup/identity-checks",Map.of("idDocument",Map.of("type","DNI","value",national()),"emails",List.of("nobody@example.test"))),"198.51.100.209"),200).path("result").asText()).isEqualTo("NEW");
            var found=result(from(postJson("/signup/family-group-lookups",Map.of("holderName","angela holder","dogName",holder.at("/dog/name").asText().toUpperCase(Locale.ROOT))),"198.51.100.209"),200);
            assertThat(found.path("result").asText()).isEqualTo("FOUND");assertThat(found.path("holderDisplayName").asText()).isEqualTo("Àngela H.");
        } finally { database.runCommand(new Document("profile",0)); }
        var reads=database.getCollection("system.profile").find(new Document("ts",new Document("$gte",since)).append("ns",new Document("$in",List.of(database.getName()+".members",database.getName()+".dogs")))
                .append("op","query")).into(new ArrayList<>());
        assertThat(reads).isNotEmpty();
        assertThat(reads).allSatisfy(read->assertThat(read.get("docsExamined",Number.class).intValue()).as("%s %s",read.getString("ns"),read.get("command")).isLessThanOrEqualTo(3));
        assertThat(holderId).isNotBlank();
    }

    // ---- Step 4.6: the SignupEdited diffs in domain_events are masked like the audit ----
    @Test void R_14_09_signupEditedDiffIsMaskedLikeTheAudit() throws Exception {
        var body=request();body.set("payment",mapper.valueToTree(Map.of("type","SEPA_DD","firstMonthOption","TODAY")));
        String id=submit(body).path("memberId").asText();String before=body.at("/person/idDocument/value").asText();String after=national();
        result(admin(patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",0,"idDocument",Map.of("type","DNI","number",after),
                "paymentMethod",Map.of("type","SEPA_DD","sepa",Map.of("iban",IBAN,"holderName","Example Holder","holderTaxId",before)))))),200);
        var edited=collection("domain_events").stream().filter(e->"SignupEdited".equals(e.getString("type"))).toList();
        assertThat(edited).hasSize(1);String json=edited.getFirst().toJson();
        assertThat(json).doesNotContain(before).doesNotContain(after).doesNotContain(IBAN).contains(after.substring(0,2)+"·····"+after.substring(7),"···· ···· ···· ···· 0001");
    }

    // ---- Step 4.7: a write conflict is retried, never answered 500 ----
    @Test void R_04_27_T_04_23_concurrentSubmissionsWithDifferentKeysGiveOneCreatedAndOnePending() throws Exception {
        for(int round=0;round<5;round++) {
            var body=request();
            var statuses=ConcurrencySupport.parallel(2,i->()->mvc.perform(from(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),"198.51.100."+(220+i+2*(sequence%10)))).andReturn().getResponse());
            assertThat(statuses.stream().map(r->r.getStatus()).toList()).as("round %s",round).containsExactlyInAnyOrder(201,422);
            assertThat(statuses.stream().filter(r->r.getStatus()==422).findFirst().orElseThrow().getContentAsString()).contains("SIGNUP_ALREADY_PENDING");
        }
    }
    @Test void R_04_27_aWriteConflictOnTheCensusLockIsRetriedAndTheSubmissionSucceeds() throws Exception {
        var transactions=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        double before=retries.retries("signup");var body=request();
        try(var held=new ConcurrencySupport.HeldTransaction(transactions,club,()->mongo.upsert(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(club+":census").and("clubId").is(club)),
                new org.springframework.data.mongodb.core.query.Update().inc("sequence",1),"census_write_locks"))) {
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var response=pool.submit(()->mvc.perform(from(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),"198.51.100.230")).andReturn().getResponse());
                ConcurrencySupport.awaitRetry(retries,"signup",before);
                held.commit();
                var answer=response.get(60,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(201);
            } finally { pool.shutdownNow(); }
        }
        assertThat(collection("members")).hasSize(1);
    }

    // ---- Step 4.8: maskedEmail has the R-04-05 shape and N-39 goes to the masked address ----
    @Test void R_04_05_maskedEmailHasTheSpecShapeAndN39GoesToTheMaskedAddress() throws Exception {
        var mailbox=(com.agilityhub.core.clubs.messaging.application.FakeEmailSender)sender;
        var body=request();String id=activeMember(body);String account=member(id).getString("accountId");
        mongo.getCollection("accounts").updateOne(new Document("_id",account),new Document("$set",new Document("email","marta.access@exemple.test")));
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("contactEmails",List.of(new Document("email","contact"+sequence+"@example.test")))));
        dispatch();mailbox.clear();
        var check=result(from(postJson("/signup/identity-checks",identity(body)),"198.51.100.231"),200);
        assertThat(check.path("maskedEmail").asText()).isEqualTo("m•••s@e•••.test");
        dispatch();
        assertThat(mailbox.messages()).hasSize(1);assertThat(mailbox.messages().getFirst().to()).isEqualTo("marta.access@exemple.test");
    }

    // ---- Step 5: GET /signup and the SIGNUP_CLOSED decision are fresh right after a change ----
    @Test void R_04_27_signupConfigurationIsFreshRightAfterItsOwnWrite() throws Exception {
        assertThat(result(get("/api/v1/signup").header("Host",host),200).path("enabled").asBoolean()).isTrue();
        result(admin(put("/api/v1/parameters/signup.enabled").header("Host",host).contentType("application/json").content("{\"value\":false,\"version\":0}")),200);
        assertThat(result(get("/api/v1/signup").header("Host",host),200).path("enabled").asBoolean()).isFalse();
        assertThat(result(from(postJson("/signup",request()).header("Idempotency-Key",UUID.randomUUID()),"198.51.100.232"),422).path("code").asText()).isEqualTo("SIGNUP_CLOSED");
        result(admin(put("/api/v1/parameters/signup.enabled").header("Host",host).contentType("application/json").content("{\"value\":true,\"version\":1}")),200);
        var config=result(get("/api/v1/signup").header("Host",host),200);assertThat(config.path("enabled").asBoolean()).isTrue();
        assertThat(config.at("/plans/0/name").asText()).isEqualTo("Example");
        result(admin(patch("/api/v1/plans/"+plan).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",0,"name",Map.of("ca","Renamed","es","Renamed","en","Renamed"))))),200);
        assertThat(result(get("/api/v1/signup").header("Host",host),200).at("/plans/0/name").asText()).isEqualTo("Renamed");
    }
}
