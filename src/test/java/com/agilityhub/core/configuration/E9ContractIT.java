package com.agilityhub.core.configuration;

import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class E9ContractIT extends AbstractIntegrationTest {
    static final String CLUB="e9-club-a", OTHER="e9-club-b", HOST="e9-a.example.test", OTHER_HOST="e9-b.example.test";
    static final List<String> DATA=List.of("courses", "placements", "ring_setups", "build_sessions", "obstacle_inventories", "calibration_logs", "venues", "rings", "levels", "activities", "dogs", "idempotency_records", "domain_events", "audit_entries");
    record Route(String method, String path, int success, boolean idempotency, boolean impersonation, List<String> roles,
                 JsonNode body, Map<String,String> params, boolean platform) { String label() { return method+" "+path; } }
    static Stream<Route> routes() throws Exception { return Arrays.stream(E9Fixtures.MAPPER.treeToValue(E9Fixtures.read("contracts/e9-routes"), Route[].class)); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;

    @BeforeEach void prepare() throws Exception {
        for (String collection : DATA) { mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("clubId").in(CLUB,OTHER), Criteria.where("_id").regex("^e9-"))), collection); }
        for (String c : List.of(CLUB,OTHER)) { club(c, List.of(Module.values())); }
        for (String c : List.of(CLUB,OTHER)) {
            String suffix=c.equals(CLUB)?"a":"other";
            for (Class<?> type : List.of(Course.class,Placement.class,RingSetup.class,BuildSession.class,ObstacleInventory.class,CalibrationLog.class,Venue.class)) {
                String kind=switch(type.getSimpleName()) { case "RingSetup" -> "setup"; case "BuildSession" -> "session"; case "ObstacleInventory" -> "inventory"; case "CalibrationLog" -> "calibration"; default -> type.getSimpleName().toLowerCase(); };
                mongo.insert(E9Fixtures.document(type,"e9-"+kind+"-"+suffix,c));
            }
            mongo.save(new Document("_id","e9-ring-"+suffix).append("clubId",c).append("name","Example ring").append("version",7L)
                    .append("geometry",Document.parse(E9Fixtures.response("RingGeometry").toString())),"rings");
            mongo.insert(new com.agilityhub.core.clubs.catalogs.persistence.Level("e9-level-"+suffix,c,"A",
                    new com.agilityhub.core.shared.domain.LocalizedText(Map.of("ca","Example level"),"ca"),1,"#123456",6,false,true,true,0L,
                    clock.instant(),clock.instant(),"actor-a","actor-a"));
            for (String ref : List.of("activity","dog")) { mongo.save(new Document("_id","e9-"+ref+"-"+suffix).append("clubId",c),ref.equals("activity")?"activities":ref+"s"); }
        }
        mongo.insert(E9Fixtures.document(Course.class,"e9-public",null));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e9-placement-a")),new Update().set("buildSheetFileKey","fictional-sheet.pdf"),Placement.class);
    }
    void club(String id,List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(id)),Club.class);
        var json=(ObjectNode)mapper.valueToTree(PlatformFixtures.club(id,id.equals(CLUB)?HOST:OTHER_HOST)); json.set("modules",mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(json,Club.class)); configs.invalidate(id); hosts.invalidate();
    }
    static String path(Route r) {
        String id=r.path().contains("/courses/")?(r.platform()?"e9-public":"e9-course-a"):
                r.path().contains("/rings/")?"e9-ring-a":r.path().contains("/placements/")?"e9-placement-a":
                r.path().contains("/ring-setups/")?"e9-setup-a":r.path().contains("/build-sessions/")?"e9-session-a":"e9-challenge-a";
        return r.path().replace("{id}",id).replace("{obstacleId}","obstacle-a").replace("{scope}","ring:e9-ring-a");
    }
    MockHttpServletRequestBuilder call(Route r,String role) throws Exception {
        var req=request(HttpMethod.valueOf(r.method()),path(r)).header("Host",HOST);
        if(r.body()!=null&&!r.body().isNull()) req.contentType("application/json").content(mapper.writeValueAsString(r.body()));
        r.params().forEach(req::param);
        if(r.idempotency()) req.header("Idempotency-Key",UUID.randomUUID().toString());
        if(!role.equals("ANON")) req.with(jwt().jwt(j->{j.subject("e9-"+role); if(!r.platform()||!role.equals("AGILITYHUB_ADMIN")) j.claim("clubId",CLUB).claim("memberId","e9-member");})
                .authorities(new SimpleGrantedAuthority("ROLE_"+role)));
        return req;
    }
    ResultActions error(MockHttpServletRequestBuilder request,int status,String code) throws Exception {
        var result=mvc.perform(request);var response=result.andReturn().getResponse();
        assertThat(response.getStatus()).as(result.andReturn().getRequest().getMethod()+" "+result.andReturn().getRequest().getRequestURI()+" "+response.getContentAsString()).isEqualTo(status);
        assertThat(mapper.readTree(response.getContentAsString()).path("code").asText())
                .as(result.andReturn().getRequest().getMethod()+" "+result.andReturn().getRequest().getRequestURI()).isEqualTo(code);
        return result.andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
    }
    Map<String,List<Document>> state() {
        var result=new TreeMap<String,List<Document>>();
        for(String c:DATA) result.put(c,mongo.findAll(Document.class,c));
        return result;
    }
    @ParameterizedTest @MethodSource("routes")
    void T_16_05_T_16_21_T_19_01_eachOperationChecksRolesHostMembershipAndWritesNothing(Route route) throws Exception {
        var before=state();
        for(String role:List.of("ANON","MEMBER","INSTRUCTOR","ADMIN","AGILITYHUB_ADMIN")) {
            boolean allowed=route.roles().contains(role);
            error(call(route,role),allowed?501:role.equals("ANON")?401:403,allowed?"NOT_IMPLEMENTED":role.equals("ANON")?"UNAUTHENTICATED":"FORBIDDEN");
        }
        String role=route.roles().getFirst();
        if(!route.platform()) {
            error(call(route,role).with(req->{req.removeHeader("Host");req.addHeader("Host",OTHER_HOST);return req;}),403,"TENANT_MISMATCH");
            error(call(route,role).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role))),403,"NO_MEMBERSHIP");
        }
        assertThat(state()).as(route.label()+" has no side effects, including idempotency").isEqualTo(before);
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_16_21_globalCourseCreationReleasesTheKeyAfter501AndValidatesTheHeader() throws Exception {
        var route=routes().filter(r->r.platform()&&r.method().equals("POST")).findFirst().orElseThrow();
        String key=UUID.randomUUID().toString();var before=state();
        for(int i=0;i<2;i++) {
            error(call(route,"AGILITYHUB_ADMIN").with(req->{req.removeHeader("Idempotency-Key");req.addHeader("Idempotency-Key",key);return req;}),501,"NOT_IMPLEMENTED");
            assertThat(state()).isEqualTo(before);
        }
        error(call(route,"AGILITYHUB_ADMIN").with(req->{req.removeHeader("Idempotency-Key");req.addHeader("Idempotency-Key","invalid");return req;}),400,"VALIDATION_ERROR");
        error(call(route,"AGILITYHUB_ADMIN").with(req->{req.removeHeader("Idempotency-Key");return req;}),501,"NOT_IMPLEMENTED");
    }
    @Test void T_16_12_everyClubRouteChecksTheModuleBeforeTheStubAndBodyValidation() throws Exception {
        club(CLUB,Arrays.stream(Module.values()).filter(m->m!=Module.COURSES).toList());
        for(var route:routes().toList()) {
            error(call(route,route.roles().getFirst()),route.platform()?501:404,route.platform()?"NOT_IMPLEMENTED":"MODULE_DISABLED");
            if(!route.platform()&&route.body()!=null) error(call(route,route.roles().getFirst()).content("{"),404,"MODULE_DISABLED");
        }
    }
    @Test void T_16_05_everyPathAndBodyOrQueryReferenceIsTenantScoped() throws Exception {
        for(var route:routes().toList()) {
            if(route.platform()||route.path().contains("challenge")) continue;
            String role=route.roles().getFirst();
            if(route.path().contains("{id}")||route.path().contains("{scope}")) {
                String actual=path(route);
                error(call(route,role).with(req->{ req.setRequestURI(actual.replace("-a","-other")); return req;}),404,"NOT_FOUND");
                error(call(route,role).with(req->{ req.setRequestURI(actual.replace("-a","-missing")); return req;}),404,"NOT_FOUND");
            }
            if(route.body()!=null) {
                for(String field:List.of("courseId","ringId","placementId","activityId","ringSetupId","dogId")) {
                    if(!route.body().hasNonNull(field)) continue;
                    var foreign=((ObjectNode)route.body()).deepCopy();foreign.put(field,foreign.path(field).asText().replace("-a","-other"));
                    error(call(route,role).content(foreign.toString()),404,"NOT_FOUND");
                }
            }
            for(String field:List.of("courseId","ringId","activityId","ringIds")) {
                if(route.params().containsKey(field)) error(call(route,role).with(req->{req.setParameter(field,route.params().get(field).replace("-a","-other"));return req;}),404,"NOT_FOUND");
            }
        }
        var join=routes().filter(r->r.path().endsWith("/join")).findFirst().orElseThrow();
        error(call(join,"INSTRUCTOR").content("{\"code\":\"UNKNOWN\"}"),404,"NOT_FOUND");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e9-session-other")),new Update().set("joinCode","OTHER42"),BuildSession.class);
        error(call(join,"INSTRUCTOR").content("{\"code\":\"OTHER42\"}"),404,"NOT_FOUND");
        var obstacle=routes().filter(r->r.path().contains("{obstacleId}")).findFirst().orElseThrow();
        error(call(obstacle,"INSTRUCTOR").with(req->{req.setRequestURI(path(obstacle).replace("obstacle-a","missing"));return req;}),404,"NOT_FOUND");
    }
    @Test void T_16_03_bothCourseWritersValidateTheRealJsonBeforeTheStub() throws Exception {
        for(var route:routes().filter(r->r.body()!=null&&r.body().has("normalizedJson")).toList()) {
            String role=route.roles().getFirst();
            var body=((ObjectNode)route.body()).deepCopy();body.put("schemaVersion",2);
            error(call(route,role).content(body.toString()),400,"SCHEMA_VERSION_UNSUPPORTED");
            body.put("schemaVersion",1);((ObjectNode)body.path("normalizedJson")).remove("title");
            error(call(route,role).content(body.toString()),400,"COURSE_MODEL_INVALID").andExpect(jsonPath("$.details.schemaErrors[0].path").exists());
            body=((ObjectNode)route.body()).deepCopy();((ObjectNode)body.path("normalizedJson")).put("extra",true);
            error(call(route,role).content(body.toString()),400,"COURSE_MODEL_INVALID");
        }
    }
    @Test void T_16_03_optionalModelAndOptionalReferencesDoNotBecomeRequired() throws Exception {
        for(var route:routes().filter(r->r.body()!=null&&r.body().has("normalizedJson")).toList()) {
            var body=((ObjectNode)route.body()).deepCopy();body.remove(List.of("normalizedJson","schemaVersion","levelIds"));
            if(route.method().equals("POST")) body.putArray("levelIds");
            error(call(route,route.roles().getFirst()).content(body.toString()),501,"NOT_IMPLEMENTED");
            body.putNull("normalizedJson");error(call(route,route.roles().getFirst()).content(body.toString()),501,"NOT_IMPLEMENTED");
        }
        var setups=routes().filter(r->r.path().equals("/api/v1/ring-setups")).findFirst().orElseThrow();
        var body=((ObjectNode)setups.body()).deepCopy();body.remove(List.of("placementId","courseId"));
        error(call(setups,"ADMIN").content(body.toString()),501,"NOT_IMPLEMENTED");
        var mine=routes().filter(r->r.path().equals("/api/v1/me/ring-setups")).findFirst().orElseThrow();
        error(call(mine,"MEMBER").with(req->{req.removeParameter("ringIds");return req;}),501,"NOT_IMPLEMENTED");
    }
    @Test void T_16_07_T_16_09_T_16_21_conditionalPermissionsApplyBefore501() throws Exception {
        for(var route:routes().filter(r->r.method().equals("POST")&&r.path().equals("/api/v1/placements")||r.method().equals("PUT")&&r.path().equals("/api/v1/placements/{id}")).toList()) {
            var forced=((ObjectNode)route.body()).deepCopy().put("force",true);
            error(call(route,"INSTRUCTOR").content(forced.toString()),403,"FORBIDDEN");error(call(route,"ADMIN").content(forced.toString()),501,"NOT_IMPLEMENTED");
        }
        parameter("courses.allowInstructorPublish",false);var publish=routes().filter(r->r.path().equals("/api/v1/ring-setups")).findFirst().orElseThrow();
        error(call(publish,"INSTRUCTOR"),403,"FORBIDDEN");error(call(publish,"ADMIN"),501,"NOT_IMPLEMENTED");
        parameter("courses.showSetupToMembers",false);
        for(var route:routes().filter(r->r.path().equals("/api/v1/ring-setups/{id}")&&r.method().equals("GET")||r.path().equals("/api/v1/me/ring-setups")).toList()) {
            error(call(route,"MEMBER"),404,"NOT_FOUND");error(call(route,"INSTRUCTOR"),501,"NOT_IMPLEMENTED");
        }
        for(var route:routes().filter(r->r.platform()&&r.body()!=null&&r.body().isObject()).toList()) {
            var body=((ObjectNode)route.body()).deepCopy();body.remove("agilityhubLevel");
            error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),400,"VALIDATION_ERROR");
            body=((ObjectNode)route.body()).deepCopy();body.putArray("levelIds").add("e9-level-a");
            error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),404,"NOT_FOUND");
        }
        var platformPatch=routes().filter(r->r.platform()&&r.method().equals("PATCH")).findFirst().orElseThrow();
        for(String id:List.of("e9-course-other","e9-missing")) {
            error(call(platformPatch,"AGILITYHUB_ADMIN").with(req->{req.setRequestURI("/api/v1/platform/courses/"+id);return req;}),404,"NOT_FOUND");
        }
        var patch=routes().filter(r->r.path().equals("/api/v1/courses/{id}")&&r.method().equals("PATCH")).findFirst().orElseThrow();
        error(call(patch,"ADMIN").with(req->{req.setRequestURI("/api/v1/courses/e9-public");return req;}),403,"FORBIDDEN");
    }
    @Test void T_16_05_onlyTheFiveMemberRoutesAcceptRealImpersonationTokens() throws Exception {
        for(String kind:List.of("admin","member")) {
            String id="e9-imp-"+kind;
            var role=kind.equals("admin")?com.agilityhub.core.identity.domain.Role.ADMIN:com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Account(id,id+"@example.test","Example "+kind,"en",null,Set.of(),
                    com.agilityhub.core.identity.persistence.Account.Status.ACTIVE,null,Map.of(),false,clock.instant()));
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(id,id,CLUB,"e9-member",Set.of(role),
                    com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE,role));
        }
        mongo.save(new Document("_id","e9-member").append("clubId",CLUB).append("accountId","e9-imp-member").append("status","ACTIVE")
                .append("firstName","Example").append("lastName1","Member").append("memberNumber",9001).append("version",0L),"members");
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try(var scope=TenantContext.open(CLUB)) { issued=impersonations.create("e9-imp-admin","e9-member","Contract test"); }
        int allowed=0;
        for(var route:routes().toList()) {
            var request=call(route,"MEMBER").with(jwt().jwt(issued.token()).authorities(()->"ROLE_MEMBER"));
            error(request,route.impersonation()?501:403,route.impersonation()?"NOT_IMPLEMENTED":"IMPERSONATION_DENIED");
            if(route.impersonation()) allowed++;
        }
        assertThat(allowed).isEqualTo(5);
    }
    @Test void T_16_05_privatePublicDeletedAndAccountCourseWritesRespectVisibility() throws Exception {
        var get=routes().filter(r->r.path().equals("/api/v1/courses/{id}")&&r.method().equals("GET")).findFirst().orElseThrow();
        var patch=routes().filter(r->r.path().equals("/api/v1/courses/{id}")&&r.method().equals("PATCH")).findFirst().orElseThrow();
        var id=Query.query(Criteria.where("_id").is("e9-course-a"));
        mongo.updateFirst(id,new Update().set("visibility","CLUB"),Course.class);
        error(call(get,"MEMBER"),404,"COURSE_NOT_VISIBLE");error(call(get,"INSTRUCTOR"),501,"NOT_IMPLEMENTED");
        mongo.updateFirst(id,new Update().set("visibility","PRIVATE").set("ownerType","ACCOUNT").set("ownerAccountId","e9-INSTRUCTOR").unset("clubId"),Course.class);
        error(call(get,"MEMBER"),404,"COURSE_NOT_VISIBLE");error(call(get,"INSTRUCTOR"),501,"NOT_IMPLEMENTED");
        error(call(patch,"INSTRUCTOR"),501,"NOT_IMPLEMENTED");
        mongo.updateFirst(id,new Update().set("visibility","PUBLIC"),Course.class);
        error(call(patch,"ADMIN"),403,"FORBIDDEN");
        // The global library can be read from either real tenant, but never through a tenant repository write.
        error(call(get,"MEMBER").with(req->{req.setRequestURI("/api/v1/courses/e9-public");return req;}),501,"NOT_IMPLEMENTED");
        error(call(get,"MEMBER").with(jwt().jwt(j->j.subject("other-member").claim("clubId",OTHER)).authorities(()->"ROLE_MEMBER"))
                .with(req->{req.setRequestURI("/api/v1/courses/e9-public");req.removeHeader("Host");req.addHeader("Host",OTHER_HOST);return req;}),501,"NOT_IMPLEMENTED");
        mongo.updateFirst(id,new Update().set("deletedAt",clock.instant()),Course.class);error(call(get,"INSTRUCTOR"),404,"NOT_FOUND");
    }
    @Test void T_16_19_missingGeometrySheetsAndMalformedScopesAreRejected() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e9-ring-a")),new Update().unset("geometry"),"rings");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e9-placement-a")),new Update().unset("buildSheetFileKey"),Placement.class);
        for(var route:routes().filter(r->r.method().equals("GET")&&(r.path().endsWith("/geometry")||r.path().endsWith("/marker-sheet")||r.path().endsWith("/build-sheet"))).toList()) {
            error(call(route,"INSTRUCTOR"),404,"NOT_FOUND");
        }
        for(var route:routes().filter(r->r.path().contains("{scope}")).toList()) {
            error(call(route,route.roles().getFirst()).with(req->{req.setRequestURI("/api/v1/obstacle-inventories/invalid");return req;}),400,"VALIDATION_ERROR");
            error(call(route,route.roles().getFirst()).with(req->{req.setRequestURI("/api/v1/obstacle-inventories/club");return req;}),501,"NOT_IMPLEMENTED");
        }
        for(var route:routes().filter(r->r.path().equals("/api/v1/placements")&&r.method().equals("POST")).toList()) {
            var body=((ObjectNode)route.body()).deepCopy();body.set("transform",mapper.createObjectNode());
            error(call(route,"ADMIN").content(body.toString()),400,"VALIDATION_ERROR");
        }
        var inventory=routes().filter(r->r.path().contains("obstacle-inventories")&&r.method().equals("PUT")).findFirst().orElseThrow();
        var negative=((ObjectNode)inventory.body()).deepCopy();((ObjectNode)negative.path("items").get(0)).put("count",-1);
        error(call(inventory,"ADMIN").content(negative.toString()),422,"INVENTORY_INVALID");
        ((ObjectNode)negative.path("items").get(0)).putNull("count");
        error(call(inventory,"ADMIN").content(negative.toString()),400,"VALIDATION_ERROR");
        var attempt=routes().filter(r->r.path().endsWith("/attempts")).findFirst().orElseThrow();
        for(String field:List.of("dogId","ringSetupId")) {
            var body=((ObjectNode)attempt.body()).deepCopy();body.put(field,body.path(field).asText().replace("-a","-other"));
            error(call(attempt,"MEMBER").content(body.toString()),404,"NOT_FOUND");
        }
        var placement=routes().filter(r->r.path().equals("/api/v1/placements")&&r.method().equals("POST")).findFirst().orElseThrow();
        var body=((ObjectNode)placement.body()).deepCopy().put("activityId","e9-activity-other");
        error(call(placement,"ADMIN").content(body.toString()),404,"NOT_FOUND");
        var create=routes().filter(r->r.path().equals("/api/v1/courses")&&r.method().equals("POST")).findFirst().orElseThrow();
        body=((ObjectNode)create.body()).deepCopy();body.putArray("levelIds").add("e9-level-other");
        error(call(create,"ADMIN").content(body.toString()),404,"NOT_FOUND");
        body.putArray("levelIds").add("e9-level-a");error(call(create,"ADMIN").content(body.toString()),501,"NOT_IMPLEMENTED");
    }
    void parameter(String key,Object value) {
        mongo.insert(new Parameter("e9-param-"+key,CLUB,key,value,"bool","club",null,List.of(),0L,clock.instant())); configs.invalidate(CLUB);
    }
    @AfterEach void removeParameters() { mongo.remove(Query.query(Criteria.where("_id").regex("^e9-param-")),"parameters");configs.invalidate(CLUB); }
}
