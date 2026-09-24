package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.*;
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
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Fictional S07 clubs `s07-a` / `s07-b`: five rings, three levels, an admin, an instructor and 32 members with a dog each;
 * the S06 ports are a transactional in-memory double. Shared by {@link ActivityIT} and {@link ActivityConcurrencyIT}.
 */
@org.springframework.context.annotation.Import(ActivityFixtures.Ports.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
abstract class ActivityFixtures extends AbstractIntegrationTest {
    static final String CLUB="s07-a",OTHER="s07-b",HOST="s07-a.example.test",KEY=UUID.randomUUID().toString();
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired ActivityRepository activities; @Autowired ActivityRegistrationRepository registrations;
    @Autowired ActivityLifecycleService lifecycle; @Autowired ActivityRegistrationService registrationService;
    @Autowired ActivityQueryService queries; @Autowired ActivityAudienceService audience; @Autowired PublicActivityService publicApi;
    @Autowired EventPublisher events; @Autowired ActivityTransactions transactions; @Autowired OutboxDispatcher dispatcher; @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;
    @Autowired SchedulingDouble schedulingProxy; SchedulingDouble scheduling;
    @BeforeEach void setup() {
        scheduling=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(schedulingProxy);
        scheduling.bookings.clear(); scheduling.training.clear();
        clock.setInstant(Instant.parse("2026-09-14T06:00:00Z"));
        for(String collection:List.of("activities","activity_registrations","ring_blocks","class_sessions","weeks","levels","rings","instructors","parameters","members","dogs","memberships","domain_events","notifications","audit_entries","idempotency_records","inactivity_periods"))
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB,OTHER)),collection);
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB,OTHER)),Club.class);
        for(String club:List.of(CLUB,OTHER)) {
            var tree=(ObjectNode)mapper.valueToTree(PlatformFixtures.club(club,club.equals(CLUB)?HOST:"s07-b.example.test"));
            tree.set("modules",mapper.valueToTree(Module.values())); tree.set("locales",mapper.valueToTree(List.of("ca","es","en"))); tree.put("publicApiKeyHash",PublicClubAccess.digest(club.equals(CLUB)?KEY:"different"));
            tree.put("websiteUrl","https://club.example.test"); clubs.save(mapper.convertValue(tree,Club.class)); configs.invalidate(club); publicApi.invalidate(club);
        }
        hosts.invalidate();
        for(int i=0;i<5;i++) mongo.save(new Document("_id","s07-ring-"+i).append("clubId",CLUB).append("name","Ring "+i).append("shortName","R"+i).append("color","#112233").append("allowsFreeTraining",true).append("active",true).append("order",i).append("version",0),"rings");
        for(String level:List.of("B","C","D")) mongo.save(new Document("_id","s07-"+level).append("clubId",CLUB).append("code",level).append("nameKeys",List.of(level.toLowerCase())).append("name",new Document("values",new Document("ca",level)).append("defaultLocale","ca")).append("active",true).append("order",1).append("capacity",5).append("grantsFreeTraining",false).append("version",0),"levels");
        member("admin","ADMIN"); member("instructor","INSTRUCTOR"); for(int i=0;i<32;i++) member("m"+i,"MEMBER");
    }
    void member(String id,String role) {
        String account="s07-"+id;
        mongo.save(new com.agilityhub.core.identity.persistence.Account(account,account+"@example.test","Example "+id,id.equals("m1")?"en":"ca",null,
                Set.of(),com.agilityhub.core.identity.persistence.Account.Status.ACTIVE,null,Map.of(),false,clock.instant()));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership(account,account,CLUB,id,Set.of(com.agilityhub.core.identity.domain.Role.valueOf(role)),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE,com.agilityhub.core.identity.domain.Role.valueOf(role)));
        mongo.save(new Document("_id",id).append("clubId",CLUB).append("accountId",account).append("firstName","Example").append("lastName1",id).append("memberNumber",id.hashCode()).append("status","ACTIVE")
                .append("bookingBlock",new Document("active",false)).append("contactEmails",List.of(new Document("email",account+"@example.test")))
                .append("phones",List.of(new Document("prefix","+34").append("number","600000001"))).append("version",0),"members");
        mongo.save(new Document("_id","dog-"+id).append("clubId",CLUB).append("memberId",id).append("name","Example dog").append("status","ACTIVE").append("levelId",id.equals("m31")?"s07-B":"s07-D").append("version",0),"dogs");
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,String member,String role) {
        return request.header("Host",HOST).with(jwt().jwt(j -> j.subject("s07-"+member).claim("clubId",CLUB).claim("memberId",member)).authorities(() -> "ROLE_"+role));
    }
    JsonNode call(String method,String path,Object body,String member,String role,int expected) throws Exception {
        var request=auth(request(HttpMethod.valueOf(method),"/api/v1"+path),member,role);
        if(body!=null) request.contentType("application/json").content(mapper.writeValueAsBytes(body));
        if(method.equals("POST")) request.header("Idempotency-Key",UUID.randomUUID().toString());
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(method+" "+path+" "+response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsByteArray().length==0?mapper.nullNode():mapper.readTree(response.getContentAsString());
    }
    JsonNode create() throws Exception { return call("POST","/activities",Map.of("title",Map.of("ca","Activitat exemple","es","Actividad ejemplo","en","Example activity"),"type","SEMINAR"),"admin","ADMIN",201); }
    JsonNode ready(int max,boolean rings) throws Exception {
        var a=create(); var patch=new LinkedHashMap<String,Object>(); patch.put("version",a.path("version").asLong()); patch.put("date","2026-09-15"); patch.put("startTime","18:00"); patch.put("endTime","20:00");
        patch.put("registrationFrom","2026-09-01"); patch.put("registrationTo","2026-09-15"); patch.put("maxPlaces",max); patch.put("waitlistEnabled",true);
        if(rings) patch.put("ringIds",java.util.stream.IntStream.range(0,5).mapToObj(i -> "s07-ring-"+i).toList());
        return call("PATCH","/activities/"+a.path("id").asText(),patch,"admin","ADMIN",200);
    }
    JsonNode published(int max,boolean rings) throws Exception { var a=ready(max,rings); return call("POST","/activities/"+a.path("id").asText()+"/publication",Map.of(),"admin","ADMIN",200); }
    JsonNode register(String id,String member,boolean wait,int expected) throws Exception { return call("POST","/activity-registrations",Map.of("activityId",id,"joinWaitlist",wait),member,"MEMBER",expected); }
    long count(String collection,String field,Object value) { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and(field).is(value)),collection); }
    void dispatch() { for(int i=0;i<5;i++) dispatcher.dispatch(); }
    void modules(Set<Module> enabled) {
        var tree=(ObjectNode)mapper.valueToTree(clubs.findById(CLUB).orElseThrow());tree.set("modules",mapper.valueToTree(enabled));clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(CLUB);publicApi.invalidate(CLUB);
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class Ports {
        @org.springframework.context.annotation.Bean SchedulingDouble activitySchedulingPorts() { return new SchedulingDouble(); }
    }
    static class SchedulingDouble implements com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort, com.agilityhub.core.clubs.scheduling.application.ports.TrainingConflictPort {
        final Map<String,List<BookingRef>> bookings=new HashMap<>();
        final List<Booking> training=new ArrayList<>();
        public List<BookingRef> activeBookings(String id) { return bookings.getOrDefault(id,List.of()); }
        public List<WaitlistRef> liveWaitlist(String id) { return List.of(); }
        public List<WaitlistRef> waitlistEntries(List<String> ids) { return List.of(); }
        public CancellationEffects cancelAllByClub(String id,String reason,String actor) {
            var old=bookings.remove(id);undo(() -> {if(old!=null) bookings.put(id,old);});return new CancellationEffects(old==null?List.of():old,List.of());
        }
        public List<Booking> findActiveBookings(String ring,Instant from,Instant to) { return training.stream().filter(b -> b.ringId().equals(ring) && b.from().isBefore(to) && from.isBefore(b.to())).toList(); }
        public void cancelByClub(List<String> ids,String reason) { var old=training.stream().filter(b -> ids.contains(b.id())).toList();training.removeAll(old);undo(() -> training.addAll(old)); }
        public void lockSlots(String ring,Instant from,Instant to) { }
        private void undo(Runnable work) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) work.run(); }
            });
        }
    }
}
