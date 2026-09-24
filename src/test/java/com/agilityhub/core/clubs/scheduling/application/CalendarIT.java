package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
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

import com.agilityhub.core.clubs.scheduling.application.ports.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.clubs.messaging.application.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(CalendarIT.Ports.class)
class CalendarIT extends AbstractIntegrationTest {
    static final String CLUB="planning-a",OTHER="planning-b",HOST="planning.example.test";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired TemplateQuery templateQuery; @Autowired CatalogRepository<Level> levels; @Autowired ClassSessionRepository sessions;
    @Autowired ClassSessionService service; @Autowired RingBlockService blocks; @Autowired ClassCancellationUseCase cancellations;
    @Autowired SchedulingTransactions transactions; @Autowired Doubles doubles; @Autowired OutboxDispatcher dispatcher;
    @Autowired EmailSender sender; @Autowired TransactionTemplate tx;
    @BeforeEach void prepare() {
        doubles=org.springframework.test.util.AopTestUtils.getTargetObject(doubles); doubles.clear();
        clock.setInstant(Instant.parse("2026-08-24T04:00:00Z"));
        for (String collection : List.of("parameters", "levels", "rings", "instructors", "week_templates", "weeks", "class_sessions", "members", "dogs", "idempotency_records", "audit_entries", "domain_events", "ring_blocks", "notifications", "memberships")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        for (String id : List.of(CLUB, OTHER)) {
            var club = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id.equals(CLUB) ? HOST : "other.planning.example.test"));
            club.set("locales", mapper.valueToTree(List.of("ca", "es", "en"))); clubs.save(mapper.convertValue(club, Club.class));
            configs.invalidate(id); templateQuery.invalidate(id);
        }
        hosts.invalidate(); int order = 0;
        for (String name : List.of("Cadells", "A", "B", "C", "D", "E", "F", "G")) {
            try (var tenant = TenantContext.open(CLUB)) { levels.insert(new Level("plan-level-" + name, CLUB, name.toUpperCase(), new LocalizedText(Map.of("ca", name, "es", name, "en", name), "ca"),
                    order++, "#112233", name.equals("C") ? 4 : 5, false, true, true, 0, clock.instant(), clock.instant(), "admin", "admin")); }
        }
        mongo.insert(new Ring("plan-ring", CLUB, "Example", "EX", "#112233", false, null, 0, true, 0, clock.instant(), clock.instant(), "admin", "admin"));
        for (String id : List.of("plan-instructor", "plan-instructor-2")) {
            mongo.insert(new Instructor(id, CLUB, id + "-member", id, "#112233", true, 0, clock.instant(), clock.instant(), "admin", "admin"));
        }
    }
    MockHttpServletRequestBuilder call(String method, String path, Object body) throws Exception {
        var request = request(HttpMethod.valueOf(method), "/api/v1" + path).header("Host", HOST)
                .with(jwt().jwt(j -> j.subject("planning-admin").claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN"));
        if(method.equals("POST") && (path.equals("/ring-blocks") || path.startsWith("/class-sessions/") && path.endsWith("/cancellation"))) request.header("Idempotency-Key",UUID.randomUUID().toString());
        if (body != null) { request.contentType("application/json").content(mapper.writeValueAsString(body)); }
        return request;
    }
    JsonNode ok(String method, String path, Object body) throws Exception {
        var response = mvc.perform(call(method, path, body)).andExpect(status().is2xxSuccessful()).andReturn().getResponse();
        return response.getContentAsByteArray().length == 0 ? mapper.nullNode() : mapper.readTree(response.getContentAsByteArray());
    }
    void error(String method, String path, Object body, ErrorCode code) throws Exception {
        mvc.perform(call(method, path, body)).andExpect(status().is(code.httpStatus())).andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    String template(String name, String kind) throws Exception { return ok("POST", "/week-templates", Map.of("name", name, "kind", kind)).path("id").asText(); }
    JsonNode band(String id) throws Exception { return ok("POST", "/week-templates/" + id + "/bands", Map.of("startTime", "18:00", "endTime", "19:00")); }
    Map<String, Object> classBody(String band) { return new LinkedHashMap<>(Map.of("bandId", band, "dayOfWeek", "MONDAY", "ringId", "plan-ring", "instructorIds", List.of("plan-instructor"), "levelIds", List.of("plan-level-B", "plan-level-C"))); }
    JsonNode patchClass(String template, String classId, long version, String key, Object value) throws Exception {
        var patch = new LinkedHashMap<String, Object>(); patch.put("version", version); patch.put(key, value);
        return ok("PATCH", "/week-templates/" + template + "/classes/" + classId, patch);
    }
    void parameter(String key, Object value, String type) {
        mongo.remove(Query.query(Criteria.where("_id").is(CLUB + ":" + key)), Parameter.class);
        mongo.insert(new Parameter(CLUB + ":" + key, CLUB, key, value, type, "club", null, List.of(), 0L, clock.instant())); configs.invalidate(CLUB);
    }
    String session(String date,String start,String ring) throws Exception {
        var body=new LinkedHashMap<String,Object>(); body.put("date",date); body.put("startTime",start); body.put("endTime",LocalTime.parse(start).plusHours(1).toString());
        body.put("ringId",ring); body.put("instructorIds",List.of("plan-instructor")); body.put("levelIds",List.of("plan-level-D","plan-level-E","plan-level-F","plan-level-G"));
        return ok("POST","/class-sessions",body).path("id").asText();
    }
    JsonNode session(String id) throws Exception { return ok("GET","/class-sessions/"+id,null); }
    void validate(String id) throws Exception { ok("POST","/weeks/"+session(id).path("weekId").asText()+"/validation",Map.of()); }
    JsonNode memberGrid(String date,boolean staff) throws Exception {
        return mapper.readTree(mvc.perform(call("GET","/day-grid?date="+date+"&view="+(staff?"instructor":"member"),null)
                .with(jwt().jwt(j -> j.subject("planning-member").claim("clubId",CLUB)).authorities(() -> staff?"ROLE_INSTRUCTOR":"ROLE_MEMBER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    JsonNode patch(String id,Map<String,Object> changes) throws Exception {
        var body=new LinkedHashMap<>(changes); body.put("version",session(id).path("version").asLong());return ok("PATCH","/class-sessions/"+id,body);
    }
    void modules(Module... modules) {
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); tree.set("modules",mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(CLUB);
    }
    void counts(String id,int booked,int waiting) { mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),new Update().set("counters",Map.of("booked",booked,"waiting",waiting)),"class_sessions"); }
    List<Document> notifications(String code) { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)),Document.class,"notifications"); }
    long events(String type) { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is(type)),"domain_events"); }
    void audit(AuditAction action) { assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is(action.name())),"audit_entries")).isPositive(); }
    @Test @AuditCovers(AuditAction.WEEK_VALIDATED)
    void T_06_12_validateAtomicallyAndExposeDraftsOnlyAfterValidation() throws Exception {
        String id=session("2026-08-25","18:00","plan-ring"), week=session(id).path("weekId").asText();
        assertThat(memberGrid("2026-08-25",false).path("rows")).isEmpty();
        var calendar=ok("GET","/weeks/"+week+"/calendar?filter=DRAFT",null); assertThat(calendar.path("draftCount").asInt()).isEqualTo(1); assertThat(calendar.path("canValidate").asBoolean()).isTrue();
        String overlap=session("2026-08-25","18:00","plan-ring");
        error("POST","/weeks/"+week+"/validation",Map.of(),ErrorCode.WEEK_INCONSISTENT); assertThat(session(id).path("state").asText()).isEqualTo("DRAFT");
        ok("POST","/class-sessions/"+overlap+"/cancellation",Map.of("reason","DELETED"));
        var result=ok("POST","/weeks/"+week+"/validation",Map.of()); assertThat(result.path("validatedClassIds")).hasSize(1); assertThat(result.at("/validatedClassIds/0").asText()).isEqualTo(id);
        assertThat(session(id).path("state").asText()).isEqualTo("ACTIVE"); assertThat(ok("GET","/weeks/"+week,null).path("validatedAt").isMissingNode()).isFalse();
        var grid=memberGrid("2026-08-25",false); assertThat(grid.path("rows")).hasSize(1); assertThat(grid.toString()).doesNotContain("occupancy","counters","notes");
        error("POST","/weeks/"+week+"/validation",Map.of(),ErrorCode.NOTHING_TO_VALIDATE); assertThat(events("WeekValidated")).isEqualTo(1);audit(AuditAction.WEEK_VALIDATED);
        String active=session("2026-08-26","19:00",null); assertThat(session(active).path("state").asText()).isEqualTo("ACTIVE");
        var list=ok("GET","/class-sessions?filter=weekId:eq:"+week+"&filter=state:eq:ACTIVE&sort=date,asc&fields=id,state",null);assertThat(list.path("items")).hasSize(2);assertThat(list.at("/items/0").fieldNames()).toIterable().containsExactlyInAnyOrder("id","state");
        error("GET","/class-sessions?filter=bad:eq:value",null,ErrorCode.INVALID_FILTER);
        assertThat(ok("GET","/weeks/"+week+"/calendar?filter=ACTIVE",null).path("classes")).hasSize(3);
        assertThat(ok("GET","/weeks/"+week+"/calendar?filter=CANCELLED",null).path("classes")).hasSize(1);
    }
    @Test @AuditCovers({AuditAction.CLASS_UPDATED_WITH_BOOKINGS,AuditAction.CLASS_RISK_EXEMPTION_CHANGED})
    void T_06_13_editPresenceCapacityVersionExemptionAndLocalizedChangeNotifications() throws Exception {
        modules(Module.SMS,Module.WAITLIST,Module.PACKS);String id=session("2026-08-25","18:00","plan-ring");validate(id);audience(id,3,0);
        var current=session(id);long version=current.path("version").asLong();
        error("PATCH","/class-sessions/"+id,Map.of("version",version,"capacity",2),ErrorCode.CAPACITY_BELOW_BOOKINGS);
        error("PATCH","/class-sessions/"+id,Map.of("version",version,"date","2026-08-26"),ErrorCode.VALIDATION_ERROR);
        patch(id,Map.of("startTime","18:10","endTime","19:10","description","Manual","capacity",4));
        error("PATCH","/class-sessions/"+id,Map.of("version",version,"notes","stale"),ErrorCode.STALE_VERSION);
        dispatcher.dispatch();assertThat(notifications("N-08b")).hasSize(10);assertThat(notifications("N-08a")).isEmpty();
        assertThat(notifications("N-08b").stream().filter(n -> n.getString("channel").equals("APP")).toList().toString()).contains("18:00","18:10","Time");
        var reset=new LinkedHashMap<String,Object>();reset.put("capacity",null);reset.put("description",null);reset.put("ringId",null);reset.put("notes",null);
        var changed=patch(id,reset);assertThat(changed.path("capacityMode").asText()).isEqualTo("AUTO");assertThat(changed.path("displayDescription").asText()).isEqualTo("D i sup.");
        dispatcher.dispatch();long notices=notifications("N-08b").size();
        patch(id,Map.of("levelIds",List.of("plan-level-B")));dispatcher.dispatch();assertThat(notifications("N-08b")).hasSize((int)notices);
        ok("POST","/class-sessions/"+id+"/risk-exemption",Map.of("exempt",true));ok("POST","/class-sessions/"+id+"/risk-exemption",Map.of("exempt",true));
        patch(id,Map.of("riskExempt",false));assertThat(events("ClassRiskExemptionChanged")).isEqualTo(2);audit(AuditAction.CLASS_UPDATED_WITH_BOOKINGS);audit(AuditAction.CLASS_RISK_EXEMPTION_CHANGED);
        String draft=session("2026-09-01","18:00",null);error("POST","/class-sessions/"+draft+"/risk-exemption",Map.of("exempt",true),ErrorCode.INVALID_STATE);
        // S10 §7 (E6-T01): attendanceSummary belongs to S10; a planning PATCH copies it untouched and cannot clear it, not even by sending it.
        var summary=new ClassSession.AttendanceSummary(3,2,1,0,1,Instant.parse("2026-08-24T03:00:00Z"),"Estel");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),new Update().set("attendanceSummary",new Document("version",3).append("marked",2).append("present",1)
                .append("notified",0).append("noShow",1).append("savedAt",Date.from(summary.savedAt())).append("savedByName","Estel")),"class_sessions");
        patch(id,Map.of("notes","after the attendance"));
        assertThat(mongo.findById(id,ClassSession.class).attendanceSummary()).isEqualTo(summary);
        var clearing=new LinkedHashMap<String,Object>();clearing.put("notes","again");clearing.put("attendanceSummary",null);clearing.put("version",session(id).path("version").asLong());
        mvc.perform(call("PATCH","/class-sessions/"+id,clearing)).andReturn();
        assertThat(mongo.findById(id,ClassSession.class).attendanceSummary()).isEqualTo(summary);
        assertThat(session(id).has("attendanceSummary")).isFalse();
    }
    @Test void T_10_09_calendarAndInstructorGridExposeTheS10AttendanceStatus() throws Exception {
        String id=session("2026-08-25","18:00","plan-ring");validate(id);String week=session(id).path("weekId").asText();counts(id,2,0);
        assertThat(calendarClass(week,id).path("attendanceStatus").asText()).isEqualTo("NONE");
        clock.setInstant(Instant.parse("2026-08-25T10:00:00Z"));
        assertThat(calendarClass(week,id).path("attendanceStatus").asText()).isEqualTo("PENDING");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),new Update().set("attendanceSummary",new Document("version",1).append("marked",2).append("present",2)
                .append("notified",0).append("noShow",0)),"class_sessions");
        assertThat(calendarClass(week,id).path("attendanceStatus").asText()).isEqualTo("DONE");
        var cell=memberGrid("2026-08-25",true).at("/rows/0/cells/0");
        assertThat(cell.path("classId").asText()).isEqualTo(id);assertThat(cell.path("attendanceStatus").asText()).isEqualTo("DONE");
        assertThat(memberGrid("2026-08-25",false).toString()).doesNotContain("attendanceStatus");
        clock.setInstant(Instant.parse("2026-08-26T21:59:59Z"));
        assertThat(calendarClass(week,id).path("attendanceStatus").asText()).isEqualTo("DONE");
        clock.setInstant(Instant.parse("2026-08-26T22:00:00Z"));
        assertThat(calendarClass(week,id).path("attendanceStatus").asText()).isEqualTo("CLOSED");
    }
    JsonNode calendarClass(String week,String id) throws Exception {
        for(var item:ok("GET","/weeks/"+week+"/calendar?filter=ACTIVE",null).path("classes")) if(item.path("id").asText().equals(id)) return item;
        throw new AssertionError("class "+id+" not in the calendar");
    }
    void person(String member,String account,String name,Role role,String locale) {
        if(account!=null) {
            mongo.save(new Account(account,account+"@example.test",name,locale,null,Set.of(),Account.Status.ACTIVE,null,Map.of(),false,clock.instant()));
            mongo.save(new Membership(account,account,CLUB,member,Set.of(role),Membership.Status.ACTIVE,role));
        }
        mongo.save(new Document("_id",member).append("clubId",CLUB).append("accountId",account).append("firstName",name).append("lastName1","Example").append("status","ACTIVE")
                .append("contactEmails",List.of(Map.of("email",member+"@example.test"))).append("phones",List.of(Map.of("prefix","+34","number","600000001"),Map.of("prefix","+34","number","600000002"))).append("signup",Map.of("locale",locale)),"members");
    }
    void audience(String id,int booked,int waiting) {
        person("plan-instructor-member","teacher-account","Teacher",Role.INSTRUCTOR,"ca");person("admin-member","admin-account","Admin",Role.ADMIN,"ca");
        for(int i=0;i<booked+waiting;i++) {
            String member="student-"+i,dog="dog-"+i;person(member,member,"Student"+i,Role.MEMBER,i==0?"en":"ca");
            mongo.save(new Document("_id",dog).append("clubId",CLUB).append("memberId",member).append("name","Dog"+i).append("levelId","plan-level-D"),"dogs");
            if(i<booked) doubles.bookings.computeIfAbsent(id,k -> new ArrayList<>()).add(new ClassBookingsPort.BookingRef("booking-"+i,member,dog,i<2));
            else doubles.waiting.computeIfAbsent(id,k -> new ArrayList<>()).add(new ClassBookingsPort.WaitlistRef("wait-"+i,member,dog));
        }
        doubles.holds.put(id,new HashSet<>(List.of("hold-1","hold-2")));
        counts(id,booked,waiting);((FakeEmailSender)sender).clear();
    }
    @Test @AuditCovers(AuditAction.CLASS_CANCELLED)
    void T_06_14_T_06_15_T_06_34_cancellationRollsBackAndDeliversEveryDogInRecipientLocaleOnce() throws Exception {
        modules(Module.SMS,Module.WAITLIST,Module.PACKS);String id=session("2026-08-25","18:00","plan-ring");validate(id);audience(id,4,2);
        var preview=ok("GET","/class-sessions/"+id+"/cancellation-preview",null);assertThat(preview.path("bookings")).hasSize(4);assertThat(preview.path("waitlistCount").asInt()).isEqualTo(2);
        assertThat(preview.at("/bookings/0/channels").toString()).isEqualTo("[\"APP\",\"EMAIL\",\"SMS\"]");assertThat(preview.at("/bookings/0/phoneCount").asInt()).isEqualTo(2);
        error("POST","/class-sessions/"+id+"/cancellation",Map.of("reason","CLUB_MANUAL"),ErrorCode.ADMIN_TEXT_REQUIRED);
        doubles.fail=true;mvc.perform(call("POST","/class-sessions/"+id+"/cancellation",Map.of("reason","CLUB_MANUAL","adminText","Rain"))).andExpect(status().isInternalServerError());doubles.fail=false;
        assertThat(session(id).path("state").asText()).isEqualTo("ACTIVE");assertThat(doubles.activeBookings(id)).hasSize(4);assertThat(doubles.liveWaitlist(id)).hasSize(2);assertThat(events("PackRefunded")).isZero();assertThat(doubles.holds.get(id)).hasSize(2);
        String key=UUID.randomUUID().toString();var body=Map.of("reason","CLUB_MANUAL","adminText","Rain: please choose another class.");
        var first=mvc.perform(call("POST","/class-sessions/"+id+"/cancellation",body).with(r -> {r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key",key);return r;})).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var repeat=mvc.perform(call("POST","/class-sessions/"+id+"/cancellation",body).with(r -> {r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key",key);return r;})).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(repeat).isEqualTo(first);
        assertThat(session(id).at("/counters/booked").asInt()).isZero();assertThat(session(id).at("/cancellation/affectedBookings").asInt()).isEqualTo(4);assertThat(session(id).at("/cancellation/affectedWaitlist").asInt()).isEqualTo(2);
        assertThat(events("PackRefunded")).isEqualTo(2);assertThat(events("ClassCancelledByClub")).isEqualTo(1);assertThat(doubles.activeBookings(id)).isEmpty();assertThat(doubles.liveWaitlist(id)).isEmpty();assertThat(doubles.holds).doesNotContainKey(id);
        dispatcher.dispatch();assertThat(notifications("N-08a")).hasSize(21);dispatcher.dispatch();assertThat(notifications("N-08a")).hasSize(21);
        var sms=notifications("N-08a").stream().filter(n -> n.getString("channel").equals("SMS")).toList();assertThat(sms).hasSize(6).allSatisfy(n -> {assertThat(n.getString("status")).isEqualTo("QUEUED");assertThat(n.getString("body").length()).isLessThanOrEqualTo(160);assertThat(n.getList("recipientPhones",String.class)).hasSize(2);});
        var english=((FakeEmailSender)sender).messages().stream().filter(m -> m.to().equals("student-0@example.test")).findFirst().orElseThrow();
        assertThat(english.text()).contains("D and up","Tuesday","Rain: please choose another class.");audit(AuditAction.CLASS_CANCELLED);
    }
    @Test void T_06_14_deletedDraftApplicantAndSmsOffAreDistinctDeliveryCases() throws Exception {
        modules(Module.WAITLIST);String id=session("2026-08-25","18:00",null);validate(id);audience(id,1,1);
        person("student-0",null,"Applicant",Role.MEMBER,"es");ok("POST","/class-sessions/"+id+"/cancellation",Map.of("reason","DELETED","adminText","Maintenance"));dispatcher.dispatch();
        assertThat(notifications("N-08a").stream().filter(n -> n.getString("channel").equals("SMS"))).allSatisfy(n -> assertThat(n.getString("status")).isEqualTo("SKIPPED_MODULE_OFF"));
        assertThat(events("PackRefunded")).isZero();long delivered=notifications("N-08a").size();String draft=session("2026-09-01","18:00",null);
        ok("POST","/class-sessions/"+draft+"/cancellation",Map.of("reason","DELETED"));dispatcher.dispatch();assertThat(notifications("N-08a")).hasSize((int)delivered);
        error("POST","/class-sessions/"+draft+"/cancellation",Map.of("reason","DELETED"),ErrorCode.INVALID_STATE);patch(draft,Map.of("notes","Keep history"));
        error("PATCH","/class-sessions/"+draft,Map.of("version",session(draft).path("version").asLong(),"capacity",3),ErrorCode.INVALID_STATE);
    }
    Map<String,Object> blockBody(String from,String to) { return new LinkedHashMap<>(Map.of("ringId","plan-ring","from",from,"to",to,"kind","BLOCK","reason","MAINTENANCE","note","Private maintenance note")); }
    @Test @AuditCovers({AuditAction.RING_BLOCK_CREATED,AuditAction.RING_BLOCK_CANCELLED})
    void T_06_16_T_06_32_ringBlockLifecycleConflictsTimeRulesRolesAndRedaction() throws Exception {
        modules(Module.FREE_TRAINING);var body=blockBody("2026-08-25T14:10:00Z","2026-08-25T14:40:00Z");
        var block=mapper.readTree(mvc.perform(call("POST","/ring-blocks",body).with(jwt().jwt(j -> j.subject("teacher").claim("clubId",CLUB)).authorities(() -> "ROLE_INSTRUCTOR"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        String id=block.path("id").asText();error("POST","/ring-blocks",body,ErrorCode.RING_BLOCK_CONFLICT);
        var grid=memberGrid("2026-08-25",false);assertThat(grid.toString()).contains("OCCUPIED","MAINTENANCE").doesNotContain("note","createdByName","blockId");
        var memberList=mapper.readTree(mvc.perform(call("GET","/ring-blocks?filter=state:eq:ACTIVE",null).with(jwt().jwt(j -> j.claim("clubId",CLUB)).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(memberList.toString()).doesNotContain("note","createdByName");assertThat(memberList.path("items")).hasSize(1);
        ok("PATCH","/ring-blocks/"+id,Map.of("version",block.path("version").asLong(),"from","2026-08-25T15:10:00Z","to","2026-08-25T15:40:00Z","kind","RESERVATION","reason","PRIVATE_CLASS"));
        error("PATCH","/ring-blocks/"+id,Map.of("version",block.path("version").asLong()),ErrorCode.STALE_VERSION);
        assertThat(memberGrid("2026-08-25",false).toString()).contains("17:10");assertThat(events("RingBlockUpdated")).isEqualTo(1);
        error("POST","/ring-blocks",blockBody("2026-08-25T14:10:00Z","2026-08-25T14:30:00Z"),ErrorCode.INVALID_TIME_RANGE);
        error("POST","/ring-blocks",blockBody("2026-08-25T14:05:00Z","2026-08-25T14:40:00Z"),ErrorCode.INVALID_SLOT_GRANULARITY);
        error("POST","/ring-blocks",blockBody("2026-08-25T04:00:00Z","2026-08-25T05:00:00Z"),ErrorCode.OUTSIDE_OPENING_HOURS);
        error("POST","/ring-blocks",blockBody("2026-08-23T14:00:00Z","2026-08-23T15:00:00Z"),ErrorCode.VALIDATION_ERROR);
        error("POST","/ring-blocks",blockBody("2026-12-23T14:00:00Z","2026-12-23T15:00:00Z"),ErrorCode.VALIDATION_ERROR);
        var invalid=new LinkedHashMap<>(body);invalid.put("reason","PRIVATE_CLASS");error("POST","/ring-blocks",invalid,ErrorCode.VALIDATION_ERROR);
        invalid.put("kind","RESERVATION");invalid.put("reason","MAINTENANCE");error("POST","/ring-blocks",invalid,ErrorCode.VALIDATION_ERROR);
        invalid=new LinkedHashMap<>(body);invalid.put("ringId","foreign");error("POST","/ring-blocks",invalid,ErrorCode.NOT_FOUND);
        invalid=new LinkedHashMap<>(body);invalid.put("reason","ACTIVITY");error("POST","/ring-blocks",invalid,ErrorCode.VALIDATION_ERROR);
        String session=session("2026-08-25","18:00","plan-ring");
        error("POST","/ring-blocks",blockBody("2026-08-25T16:00:00Z","2026-08-25T17:00:00Z"),ErrorCode.RING_BLOCK_CONFLICT);
        error("POST","/class-sessions",Map.of("date","2026-08-25","startTime","17:10","endTime","17:40","ringId","plan-ring","instructorIds",List.of("plan-instructor"),"levelIds",List.of("plan-level-B")),ErrorCode.RING_BLOCKED);
        clock.setInstant(Instant.parse("2026-08-25T15:10:00Z"));error("PATCH","/ring-blocks/"+id,Map.of("version",1),ErrorCode.INVALID_STATE);
        ok("POST","/ring-blocks/"+id+"/cancellation",Map.of());error("POST","/ring-blocks/"+id+"/cancellation",Map.of(),ErrorCode.INVALID_STATE);
        audit(AuditAction.RING_BLOCK_CREATED);audit(AuditAction.RING_BLOCK_CANCELLED);
    }
    @Test void T_06_16_trainingConflictsCancelAtomicallyOnlyForAdmin() throws Exception {
        modules(Module.FREE_TRAINING);var from=Instant.parse("2026-08-25T16:00:00Z");
        doubles.training.add(new TrainingConflictPort.Booking("training-a","plan-ring",from,from.plusSeconds(1800),"Example","Dog"));
        var body=blockBody(from.toString(),from.plusSeconds(3600).toString());error("POST","/ring-blocks",body,ErrorCode.RING_HAS_BOOKINGS);
        body.put("cancelBookings",true);mvc.perform(call("POST","/ring-blocks",body).with(jwt().jwt(j -> j.claim("clubId",CLUB)).authorities(() -> "ROLE_INSTRUCTOR"))).andExpect(status().isForbidden());
        doubles.fail=true;mvc.perform(call("POST","/ring-blocks",body)).andExpect(status().isInternalServerError());doubles.fail=false;
        assertThat(doubles.training).hasSize(1);assertThat(ok("GET","/ring-blocks",null).path("items")).isEmpty();
        ok("POST","/ring-blocks",body);assertThat(doubles.training).isEmpty();
        doubles.training.add(new TrainingConflictPort.Booking("training-b","plan-ring",from.plusSeconds(7200),from.plusSeconds(9000),"Example","Dog"));
        var classBody=new LinkedHashMap<String,Object>(Map.of("date","2026-08-25","startTime","20:00","endTime","21:00","ringId","plan-ring","instructorIds",List.of("plan-instructor"),"levelIds",List.of("plan-level-B")));
        error("POST","/class-sessions",classBody,ErrorCode.RING_HAS_BOOKINGS);classBody.put("cancelBookings",true);ok("POST","/class-sessions",classBody);assertThat(doubles.training).isEmpty();
    }
    @Test void T_06_16_activitySyncJoinsCallerTransactionMovesAndCancelsWithFullRollback() throws Exception {
        modules(Module.ACTIVITIES,Module.FREE_TRAINING,Module.PACKS);String id=session("2026-08-25","18:00","plan-ring");validate(id);audience(id,1,0);
        var from=Instant.parse("2026-08-25T16:00:00Z");var request=new ActivityBlockRequest("activity-1",List.of("plan-ring"),from,from.plusSeconds(3600),"admin-account");
        try(var tenant=TenantContext.open(CLUB)) {
            assertThat(blocks.conflictsFor(request).conflicts()).hasSize(1);
            assertThatThrownBy(() -> blocks.syncForActivity(request,new RingBlockService.Options(false,false,null))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            staff(() -> {
                assertThatThrownBy(() -> tx.executeWithoutResult(t -> blocks.syncForActivity(request,new RingBlockService.Options(false,false,null)))).isInstanceOf(ApiException.class);
                assertThatThrownBy(() -> tx.executeWithoutResult(t -> blocks.syncForActivity(request,new RingBlockService.Options(false,true,null)))).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.ADMIN_TEXT_REQUIRED));
                assertThatThrownBy(() -> tx.executeWithoutResult(t -> { blocks.syncForActivity(request,new RingBlockService.Options(false,true,"Activity"));throw new IllegalStateException("rollback"); })).isInstanceOf(IllegalStateException.class);
                assertThat(sessions.findById(id).orElseThrow().state()).isEqualTo(ClassState.ACTIVE);assertThat(doubles.activeBookings(id)).hasSize(1);
                tx.executeWithoutResult(t -> blocks.syncForActivity(request,new RingBlockService.Options(false,true,"Activity")));
                tx.executeWithoutResult(t -> blocks.syncForActivity(request,new RingBlockService.Options(false,false,null)));
            });
        }
        var block=ok("GET","/ring-blocks",null).at("/items/0");String blockId=block.path("id").asText();assertThat(events("RingBlockCreated")).isEqualTo(1);
        error("PATCH","/ring-blocks/"+blockId,Map.of("version",0),ErrorCode.RING_BLOCK_MANAGED_BY_ACTIVITY);error("POST","/ring-blocks/"+blockId+"/cancellation",Map.of(),ErrorCode.RING_BLOCK_MANAGED_BY_ACTIVITY);
        assertThat(memberGrid("2026-08-25",false).toString()).contains("ACTIVITY","Example activity");
        try(var tenant=TenantContext.open(CLUB)) {
            staff(() -> tx.executeWithoutResult(t -> blocks.syncForActivity(new ActivityBlockRequest("activity-1",List.of("plan-ring"),from.plusSeconds(3600),from.plusSeconds(7200),"admin-account"),new RingBlockService.Options(false,false,null))));
            assertThat(events("RingBlockUpdated")).isEqualTo(1);
            staff(() -> tx.executeWithoutResult(t -> blocks.syncForActivity(new ActivityBlockRequest("activity-1",List.of(),from,from.plusSeconds(3600),"admin-account"),new RingBlockService.Options(false,false,null))));
            tx.executeWithoutResult(t -> blocks.cancelForActivity("activity-1"));
        }
        assertThat(ok("GET","/ring-blocks/"+blockId,null).path("state").asText()).isEqualTo("CANCELLED");
    }
    static void staff(Runnable action) {
        var context=org.springframework.security.core.context.SecurityContextHolder.getContext();var before=context.getAuthentication();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin","",List.of(() -> "ROLE_ADMIN")));
        try {action.run();} finally {context.setAuthentication(before);}
    }
    @Test void T_06_17_T_06_35_T_06_36_gridVisibilityModulesNoRingAndForeignTenant() throws Exception {
        modules(Module.FREE_TRAINING,Module.WAITLIST,Module.COURSES,Module.ACTIVITIES);
        String id=session("2026-08-25","18:00",null);validate(id);String draft=session("2026-09-01","18:00","plan-ring");
        counts(id,1,2);assertThat(memberGrid("2026-08-25",false).at("/rows/0/cells/0/instructorName").isMissingNode()).isTrue();
        assertThat(memberGrid("2026-08-25",true).at("/rows/0/cells/0/instructorName").asText()).isEqualTo("plan-instructor");
        clock.setInstant(Instant.parse("2026-08-24T17:00:00Z"));assertThat(memberGrid("2026-08-25",false).at("/rows/0/cells/0/instructorName").asText()).isEqualTo("plan-instructor");
        clock.setInstant(Instant.parse("2026-08-24T04:00:00Z"));parameter("bookings.showInstructorHoursBefore",0,"int");assertThat(memberGrid("2026-08-25",false).at("/rows/0/cells/0/instructorName").asText()).isEqualTo("plan-instructor");
        assertThat(memberGrid("2026-08-25",true).path("columns")).hasSize(2);assertThat(memberGrid("2026-08-26",true).path("rows")).isEmpty();assertThat(memberGrid("2026-08-26",true).path("columns")).hasSize(1);
        var from=Instant.parse("2026-08-25T08:00:00Z");doubles.training.add(new TrainingConflictPort.Booking("training","plan-ring",from,from.plusSeconds(1800),"Private guide","Private dog"));
        var member=memberGrid("2026-08-25",false);assertThat(member.toString()).contains("OCCUPIED","TRAINING").doesNotContain("Private","occupancy","who","trainingBookingIds");
        assertThat(memberGrid("2026-08-25",true).toString()).contains("Private guide","trainingBookingIds","waiting");
        mvc.perform(call("GET","/class-sessions/"+id,null).with(jwt().jwt(j -> j.claim("clubId",CLUB)).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isOk()).andExpect(jsonPath("$.freeSeats").value(4)).andExpect(jsonPath("$.notes").doesNotExist()).andExpect(jsonPath("$.counters").doesNotExist());
        mvc.perform(call("GET","/class-sessions/"+draft,null).with(jwt().jwt(j -> j.claim("clubId",CLUB)).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isNotFound());
        String active=session("2026-08-25","20:00","plan-ring");ok("POST","/class-sessions/"+active+"/cancellation",Map.of("reason","CLUB_MANUAL"));
        assertThat(memberGrid("2026-08-25",false).toString()).doesNotContain("CANCELLED");assertThat(memberGrid("2026-08-25",true).toString()).contains("CANCELLED");
        modules();var noModules=memberGrid("2026-08-25",true);assertThat(noModules.toString()).doesNotContain("waiting","TRAINING","placementId","activeSetupId");
        var body=blockBody("2026-08-26T14:00:00Z","2026-08-26T15:00:00Z");body.put("kind","RESERVATION");body.put("reason","PRIVATE_CLASS");error("POST","/ring-blocks",body,ErrorCode.MODULE_DISABLED);
        var foreign=mvc.perform(call("GET","/day-grid?date=2026-08-25",null).with(r -> {r.removeHeader("Host");r.addHeader("Host","other.planning.example.test");return r;}).with(jwt().jwt(j -> j.claim("clubId",OTHER)).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isOk()).andReturn().getResponse();assertThat(foreign.getContentAsString()).doesNotContain(id,"Private");
    }
    @Test void T_06_23_T_06_24_validationPatchAndCancellationRacesSerializeWithoutDrafts() throws Exception {
        String id=session("2026-08-25","18:00","plan-ring"),week=session(id).path("weekId").asText();long version=session(id).path("version").asLong();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var latch=new CountDownLatch(1);var validation=pool.submit(() -> {latch.await();return mvc.perform(call("POST","/weeks/"+week+"/validation",Map.of())).andReturn().getResponse().getStatus();});
            var patch=pool.submit(() -> {latch.await();return mvc.perform(call("PATCH","/class-sessions/"+id,Map.of("version",version,"notes","Concurrent"))).andReturn().getResponse().getStatus();});
            latch.countDown();assertThat(validation.get(30,TimeUnit.SECONDS)).isEqualTo(200);assertThat(patch.get(30,TimeUnit.SECONDS)).isIn(200,409);
            assertThat(session(id).path("state").asText()).isEqualTo("ACTIVE");
            var cancelLatch=new CountDownLatch(1);var futures=java.util.stream.IntStream.range(0,2).mapToObj(i -> pool.submit(() -> {cancelLatch.await();return mvc.perform(call("POST","/class-sessions/"+id+"/cancellation",Map.of("reason","CLUB_MANUAL"))).andReturn().getResponse().getStatus();})).toList();
            cancelLatch.countDown();assertThat(List.of(futures.get(0).get(30,TimeUnit.SECONDS),futures.get(1).get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(events("ClassCancelledByClub")).isEqualTo(1);
    }
    @Test void T_06_25_finishingGraceIsIdempotentAndRiskReviewReusesCancellationWithoutN08a() throws Exception {
        String id=session("2026-08-25","18:00","plan-ring");validate(id);
        try(var tenant=TenantContext.open(CLUB)) {
            var c=sessions.findById(id).orElseThrow();assertThat(service.finishEnded(c.endsAt().plusSeconds(899))).isZero();assertThat(service.finishEnded(c.endsAt().plusSeconds(900))).isEqualTo(1);assertThat(service.finishEnded(c.endsAt().plusSeconds(900))).isZero();
        }
        assertThat(session(id).path("state").asText()).isEqualTo("FINISHED");patch(id,Map.of("notes","Finished notes"));assertThat(mongo.findById(id,Document.class,"class_sessions").get("finishedAt")).isNotNull();
        error("PATCH","/class-sessions/"+id,Map.of("version",session(id).path("version").asLong(),"capacity",3),ErrorCode.INVALID_STATE);
        String risk=session("2026-08-26","18:00","plan-ring");audience(risk,1,0);
        try(var tenant=TenantContext.open(CLUB)) { cancellations.cancel(risk,ClassCancellationReason.RISK_REVIEW,"Minimum not reached",null); }
        dispatcher.dispatch();assertThat(notifications("N-08a")).isEmpty();assertThat(session(risk).at("/cancellation/reason").asText()).isEqualTo("RISK_REVIEW");
    }
    @Autowired com.agilityhub.core.clubs.dashboard.application.ports.RiskReviewSource dashboardRisk;
    @Autowired com.agilityhub.core.clubs.dashboard.application.ports.ClassOccupancyQuery occupancy;
    @Autowired FinishEndedCommand finishCommand;
    @Test void T_06_25_dashboardCoverageAndCliUseRealSessionsAndEnforceTenant() throws Exception {
        String id=session("2026-08-25","18:00","plan-ring");validate(id);audience(id,1,0);
        var from=Instant.parse("2026-08-23T22:00:00Z");var to=from.plusSeconds(7*86400);
        try(var tenant=TenantContext.open(CLUB)) {
            assertThat(occupancy.sessions(CLUB,from,to)).hasSize(1).first().satisfies(c -> {assertThat(c.booked()).isEqualTo(1);assertThat(c.capacity()).isEqualTo(5);});
            var rows=dashboardRisk.rows(CLUB,LocalDate.of(2026,8,24));assertThat(rows).hasSize(1);assertThat(rows.getFirst().description().resolve("en").value()).isEqualTo("D and up");assertThat(rows.getFirst().status()).isEqualTo("WILL_CANCEL");
            assertThatThrownBy(() -> occupancy.sessions(OTHER,from,to)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> dashboardRisk.rows(OTHER,LocalDate.of(2026,8,24))).isInstanceOf(ApiException.class);
            assertThat(sessions.findActiveBetween(CLUB,from,to)).hasSize(1);assertThat(sessions.countFutureByRing("plan-ring",clock.instant())).isEqualTo(1);assertThat(sessions.countFutureByInstructor("plan-instructor",clock.instant())).isEqualTo(1);
        }
        var coverage=ok("GET","/coverage?weekId="+session(id).path("weekId").asText(),null);assertThat(coverage.path("levels").findValuesAsText("booked")).contains("1");
        modules(Module.COURSES);mongo.updateFirst(Query.query(Criteria.where("_id").is("plan-ring")),new Update().set("activeSetupId","setup-example"),"rings");
        assertThat(memberGrid("2026-08-25",false).at("/columns/0/activeSetupId").asText()).isEqualTo("setup-example");
        clock.setInstant(Instant.parse("2026-08-25T17:15:00Z"));
        finishCommand.run(new org.springframework.boot.DefaultApplicationArguments("--core.command=scheduling:finish-ended","--club="+CLUB));
        finishCommand.run(new org.springframework.boot.DefaultApplicationArguments("--core.command=scheduling:finish-ended"));
        assertThat(session(id).path("state").asText()).isEqualTo("FINISHED");
        assertThatThrownBy(() -> finishCommand.run(new org.springframework.boot.DefaultApplicationArguments("--club=missing"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> finishCommand.run(new org.springframework.boot.DefaultApplicationArguments("--club"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finishCommand.run(new org.springframework.boot.DefaultApplicationArguments("unexpected"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void T_06_12_T_06_36_validationSeesTrainingAndActiveBlocksAndHonorsModuleRemoval() throws Exception {
        modules(Module.FREE_TRAINING,Module.ACTIVITIES);String id=session("2026-08-25","18:00","plan-ring");String week=session(id).path("weekId").asText();
        var from=Instant.parse("2026-08-25T16:00:00Z");doubles.training.add(new TrainingConflictPort.Booking("training","plan-ring",from,from.plusSeconds(1800),"Private","Dog"));
        error("POST","/weeks/"+week+"/validation",Map.of(),ErrorCode.WEEK_INCONSISTENT);assertThat(ok("GET","/weeks/"+week+"/calendar?filter=DRAFT",null).toString()).contains("RING_TRAINING_CONFLICT");
        modules();assertThat(ok("GET","/weeks/"+week+"/calendar?filter=DRAFT",null).toString()).doesNotContain("RING_TRAINING_CONFLICT");
        mongo.insert(new RingBlock("generated-conflict",CLUB,"plan-ring",from.minusSeconds(86400),from.plusSeconds(3600),RingBlockKind.BLOCK,RingBlockReason.ACTIVITY,null,"old-activity",RingBlockState.ACTIVE,null,null,0L,clock.instant(),"admin",clock.instant(),"admin"));
        assertThat(memberGrid("2026-08-25",false).toString()).doesNotContain("ACTIVITY");
        error("POST","/weeks/"+week+"/validation",Map.of(),ErrorCode.WEEK_INCONSISTENT);
        assertThat(ok("GET","/weeks/"+week+"/calendar?filter=DRAFT",null).toString()).contains("RING_BLOCKED");
        mongo.remove(Query.query(Criteria.where("_id").is("generated-conflict")),"ring_blocks");validate(id);
        var invalid=new LinkedHashMap<String,Object>();invalid.put("version",session(id).path("version").asLong());invalid.put("instructorIds",List.of("plan-instructor","plan-instructor-2"));
        error("PATCH","/class-sessions/"+id,invalid,ErrorCode.TOO_MANY_INSTRUCTORS);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Ports {
        @Bean @org.springframework.context.annotation.Primary Doubles schedulingTestPorts(EventPublisher events,ClubConfigService configs,java.time.Clock clock) { return new Doubles(events,configs,clock); }
    }
    static class Doubles implements ClassBookingsPort,TrainingConflictPort,TrainingOccupancyPort,ActivityTitlePort {
        final Map<String,List<BookingRef>> bookings=new HashMap<>();final Map<String,List<WaitlistRef>> waiting=new HashMap<>();final Map<String,Set<String>> holds=new HashMap<>();final Set<String> cancelled=new HashSet<>();final List<TrainingConflictPort.Booking> training=new ArrayList<>();boolean fail;
        private final EventPublisher events;private final ClubConfigService configs;private final java.time.Clock clock;
        Doubles(EventPublisher events,ClubConfigService configs,java.time.Clock clock) {this.events=events;this.configs=configs;this.clock=clock;}
        void clear() {bookings.clear();waiting.clear();holds.clear();cancelled.clear();training.clear();fail=false;}
        public List<BookingRef> activeBookings(String id) {return cancelled.contains(id)?List.of():List.copyOf(bookings.getOrDefault(id,List.of()));}
        public List<WaitlistRef> liveWaitlist(String id) {return cancelled.contains(id)?List.of():List.copyOf(waiting.getOrDefault(id,List.of()));}
        public List<WaitlistRef> waitlistEntries(List<String> ids) {return waiting.values().stream().flatMap(List::stream).filter(w -> ids.contains(w.entryId())).toList();}
        public List<BookingRef> clubCancelled(String id) {return cancelled.contains(id)?List.copyOf(bookings.getOrDefault(id,List.of())):List.of();}
        public List<BookingRef> bookings(List<String> ids) {return bookings.values().stream().flatMap(List::stream).filter(b -> ids.contains(b.bookingId())).toList();}
        public CancellationEffects cancelAllByClub(String id,String reason,String actor) {
            var result=new CancellationEffects(activeBookings(id),liveWaitlist(id));boolean was=cancelled.contains(id);cancelled.add(id);
            var removedHolds=holds.remove(id);
            undo(() -> {if(!was) cancelled.remove(id);if(removedHolds!=null) holds.put(id,removedHolds);});
            if(configs.get(TenantContext.require()).modules().contains(Module.PACKS)) for(var booking:result.bookings()) if(booking.paidWithPack()) events.publish(new Refund(TenantContext.require(),booking.bookingId(),clock.instant(),Map.of("bookingId",booking.bookingId()),actor));
            if(fail) throw new IllegalStateException("Forced booking failure");return result;
        }
        public List<TrainingConflictPort.Booking> findActiveBookings(String ring,Instant from,Instant to) {return training.stream().filter(b -> b.ringId().equals(ring) && b.from().isBefore(to) && from.isBefore(b.to())).toList();}
        public void cancelByClub(List<String> ids,String reason) {var removed=training.stream().filter(b -> ids.contains(b.id())).toList();training.removeAll(removed);undo(() -> training.addAll(removed));if(fail) throw new IllegalStateException("Forced training failure");}
        public void lockSlots(String ring,Instant from,Instant to) {}
        public List<Interval> occupancy(Instant from,Instant to,Collection<String> rings,String role) {return training.stream().filter(b -> b.from().isBefore(to) && from.isBefore(b.to())).map(b -> new Interval(b.ringId(),b.from(),b.to(),Type.TRAINING,"TRAINING",b.memberName(),b.dogName(),null,b.id())).toList();}
        public Map<String,String> titles(Collection<String> ids,Locale locale) {var titles=new HashMap<String,String>();ids.forEach(id -> titles.put(id,"Example activity"));return titles;}
        private void undo(Runnable action) {assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {public void afterCompletion(int status) {if(status!=STATUS_COMMITTED) action.run();}});}
    }
    record Refund(String clubId,String aggregateId,Instant occurredAt,Map<String,Object> payload,String actorAccountId) implements DomainEvent {
        public String type(){return "PackRefunded";}public String aggregateType(){return "Pack";}public String impersonatedMemberId(){return null;}public Origin origin(){return Origin.BACKOFFICE;}
    }
}
