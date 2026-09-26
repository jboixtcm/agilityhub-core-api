package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.support.SnapshotSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E5-T20: the api points the web found running D7 and the app against the real core (E4-W05 questions 1, 2 and 3, E4-W11
 * review #1), as S07 §6 and CONVENCIONS_API §4 were amended on 26-09.
 */
class ActivityRealCoreFollowUpsIT extends ActivityFixtures {
    static final String RING_CONFLICTS = "/paths/~1api~1v1~1activities~1{id}~1ring-conflicts/get/responses";

    /** Step 1 (E4-W11 review #1): the preview refuses the window the publication would refuse, and the contract says so. */
    @Test void T_07_04_R_07_05_ringConflictsAnswersTheErrorsOfTheWindowThePublicationWouldRefuse() throws Exception {
        var early=ready(5,true); String earlyId=early.path("id").asText();
        call("PATCH","/activities/"+earlyId,Map.of("version",early.path("version").asLong(),"startTime","06:00","endTime","08:00"),"admin","ADMIN",200);
        assertThat(call("GET","/activities/"+earlyId+"/ring-conflicts",null,"admin","ADMIN",422).path("code").asText()).isEqualTo("OUTSIDE_OPENING_HOURS");
        assertThat(call("POST","/activities/"+earlyId+"/publication",Map.of(),"admin","ADMIN",422).path("code").asText()).isEqualTo("OUTSIDE_OPENING_HOURS");
        // Rings without hours, or rings without a date: there is no window to block.
        var hourless=create();
        call("PATCH","/activities/"+hourless.path("id").asText(),Map.of("version",hourless.path("version").asLong(),"date","2026-09-15","ringIds",List.of("s07-ring-0")),"admin","ADMIN",200);
        assertThat(call("GET","/activities/"+hourless.path("id").asText()+"/ring-conflicts",null,"admin","ADMIN",400).path("code").asText()).isEqualTo("INVALID_TIME_RANGE");
        var undated=create();
        call("PATCH","/activities/"+undated.path("id").asText(),Map.of("version",undated.path("version").asLong(),"ringIds",List.of("s07-ring-0")),"admin","ADMIN",200);
        assertThat(call("GET","/activities/"+undated.path("id").asText()+"/ring-conflicts",null,"admin","ADMIN",400).path("code").asText()).isEqualTo("INVALID_TIME_RANGE");
        // The contract declares both, next to the codes it already had.
        var responses=openApi().at(RING_CONFLICTS);
        assertThat(responses.path("400").path("description").asText()).contains("VALIDATION_ERROR","INVALID_TIME_RANGE");
        assertThat(responses.path("422").path("description").asText()).isEqualTo("OUTSIDE_OPENING_HOURS");
    }

    /**
     * Step 3 (E4-W05 question 1, CONVENCIONS_API §4): with `fields`, a key that was not requested is left out of the item, never
     * `null` or `false` in its place; the row id always comes. Without `fields`, every property is sent, `null` where it does
     * not apply. The item schemas require only the row id, and each list publishes its keys in `x-fields`.
     */
    @Test void T_07_21_withFieldsBothD7ListsLeaveOutEveryKeyThatWasNotRequested() throws Exception {
        String atClub=ready(40,true).path("id").asText(), id=published(1,false).path("id").asText();
        register(id,"m0",false,201); register(id,"m1",true,201);
        for(var item:call("GET","/activities",null,"admin","ADMIN",200).path("items")) SnapshotSchemas.assertConforms(item,"ActivityListItem");
        for(String role:List.of("ADMIN","INSTRUCTOR")) {
            var titles=call("GET","/activities?fields=title",null,role.toLowerCase(),role,200).path("items");
            assertThat(titles).hasSize(2).allSatisfy(item -> assertThat(SnapshotSchemas.keys(item)).as(role+" "+item).containsExactly("id","title"));
        }
        var sparse=call("GET","/activities?fields=allRings,location",null,"admin","ADMIN",200).path("items");
        var rings=find(sparse,"id",atClub);
        assertThat(SnapshotSchemas.keys(rings)).containsExactly("allRings","id","location");
        assertThat(rings.path("allRings").asBoolean()).isTrue();
        assertThat(rings.path("location").isNull()).as("requested, and null at the club").isTrue();
        assertThat(SnapshotSchemas.keys(find(sparse,"id",id))).containsExactly("allRings","id","location");
        for(var item:sparse) assertThat(SnapshotSchemas.violations(item,"ActivityListItem")).as(item.toString()).isEmpty();
        call("GET","/activities?fields=ringIds",null,"admin","ADMIN",400);
        // The registrations of D7: the row id is registrationId.
        String path="/activities/"+id+"/registrations";
        var full=call("GET",path,null,"admin","ADMIN",200).path("items");
        assertThat(full).hasSize(2); for(var item:full) SnapshotSchemas.assertConforms(item,"ActivityRegistrationListItem");
        var states=call("GET",path+"?fields=state",null,"instructor","INSTRUCTOR",200).path("items");
        assertThat(states).hasSize(2).allSatisfy(item -> assertThat(SnapshotSchemas.keys(item)).as(item.toString()).containsExactly("registrationId","state"));
        assertThat(states).extracting(item -> item.path("registrationId").asText()).containsExactlyInAnyOrderElementsOf(full.findValuesAsText("registrationId"));
        var nulls=call("GET",path+"?fields=position,cancelReason",null,"admin","ADMIN",200).path("items");
        assertThat(nulls).allSatisfy(item -> assertThat(SnapshotSchemas.keys(item)).containsExactly("cancelReason","position","registrationId"));
        var active=nulls.get(0).path("position").isNull()?nulls.get(0):nulls.get(1);
        assertThat(active.path("cancelReason").isNull()).as("requested and null: "+active).isTrue();
        for(var item:nulls) assertThat(SnapshotSchemas.violations(item,"ActivityRegistrationListItem")).as(item.toString()).isEmpty();
        assertThat(call("GET",path+"?fields=id",null,"admin","ADMIN",400).path("code").asText()).as("not a key of the item").isEqualTo("INVALID_FILTER");
        // The contract: only the row id is required, and x-fields is the item's properties.
        assertThat(SnapshotSchemas.required("ActivityListItem")).containsExactly("id");
        assertThat(SnapshotSchemas.required("ActivityRegistrationListItem")).containsExactly("registrationId");
        var api=openApi();
        assertThat(strings(api.at("/paths/~1api~1v1~1activities/get/x-fields"))).containsExactlyInAnyOrderElementsOf(SnapshotSchemas.properties("ActivityListItem"));
        assertThat(strings(api.at("/paths/~1api~1v1~1activities~1{id}~1registrations/get/x-fields"))).containsExactlyInAnyOrderElementsOf(SnapshotSchemas.properties("ActivityRegistrationListItem"));
    }

    /**
     * Step 4 (E4-W05 question 2, R-07-08): a WAITLISTED registration carries `waitlistRank`, its 1-based rank among the activity's
     * waiting entries in `position` order, computed when it is read; `position` keeps the stored order. On D7's list and on the
     * member's views of the activity and of their registrations.
     */
    @Test void R_07_08_T_07_15_aWaitlistedRegistrationReadsItsRankAmongTheWaitingEntries() throws Exception {
        String id=published(1,false).path("id").asText(); String path="/activities/"+id+"/registrations";
        var first=register(id,"m0",false,201); var waiting=new ArrayList<JsonNode>();
        for(String member:List.of("m1","m2","m3")) waiting.add(register(id,member,true,201));
        assertThat(waiting).extracting(r -> r.path("waitlistRank").asInt()).containsExactly(1,2,3);
        assertThat(first.has("waitlistRank")).isTrue(); assertThat(first.path("waitlistRank").isNull()).as("ACTIVE").isTrue();
        var rows=call("GET",path,null,"admin","ADMIN",200).path("items");
        assertThat(find(rows,"registrationId",first.path("id").asText()).path("waitlistRank").isNull()).isTrue();
        for(int i=0;i<3;i++) assertThat(find(rows,"registrationId",waiting.get(i).path("id").asText()).path("waitlistRank").asInt()).isEqualTo(i+1);
        // m2 leaves the waiting list: m3 keeps position 3 and now reads rank 2.
        call("POST","/activity-registrations/"+waiting.get(1).path("id").asText()+"/cancellation",Map.of(),"m2","MEMBER",200);
        var m3=find(call("GET",path,null,"admin","ADMIN",200).path("items"),"registrationId",waiting.get(2).path("id").asText());
        assertThat(m3.path("position").asInt()).isEqualTo(3); assertThat(m3.path("waitlistRank").asInt()).isEqualTo(2);
        // m0 cancels: m1 is promoted, and the next entry (m3) reads rank 1 everywhere.
        call("POST","/activity-registrations/"+first.path("id").asText()+"/cancellation",Map.of(),"m0","MEMBER",200);
        rows=call("GET",path,null,"admin","ADMIN",200).path("items");
        var promoted=find(rows,"registrationId",waiting.get(0).path("id").asText());
        assertThat(promoted.path("state").asText()).isEqualTo("ACTIVE"); assertThat(promoted.path("waitlistRank").isNull()).isTrue();
        assertThat(find(rows,"registrationId",waiting.get(1).path("id").asText()).path("waitlistRank").isNull()).as("CANCELLED").isTrue();
        m3=find(rows,"registrationId",waiting.get(2).path("id").asText());
        assertThat(m3.path("state").asText()).isEqualTo("WAITLISTED"); assertThat(m3.path("position").asInt()).isEqualTo(3); assertThat(m3.path("waitlistRank").asInt()).isEqualTo(1);
        for(var row:rows) SnapshotSchemas.assertConforms(row,"ActivityRegistrationListItem");
        assertThat(call("GET",path+"?fields=waitlistRank",null,"admin","ADMIN",200).path("items"))
                .allSatisfy(item -> assertThat(SnapshotSchemas.keys(item)).containsExactly("registrationId","waitlistRank"));
        // The member's views.
        String m3Id=waiting.get(2).path("id").asText();
        var own=call("GET","/activity-registrations/"+m3Id,null,"m3","MEMBER",200);
        assertThat(own.path("waitlistRank").asInt()).isEqualTo(1); assertThat(own.path("position").asInt()).isEqualTo(3);
        SnapshotSchemas.assertConforms(own,"ActivityRegistration");
        var mine=find(call("GET","/me/activities",null,"m3","MEMBER",200).path("mine"),"id",m3Id);
        assertThat(mine.path("waitlistRank").asInt()).isEqualTo(1); SnapshotSchemas.assertConforms(mine,"ActivityRegistrationSummary");
        var detail=call("GET","/me/activities/"+id,null,"m3","MEMBER",200).path("myRegistration");
        assertThat(detail.path("waitlistRank").asInt()).isEqualTo(1); SnapshotSchemas.assertConforms(detail,"ActivityRegistration");
        var next=register(id,"m4",true,201);
        assertThat(next.path("position").asInt()).isEqualTo(4); assertThat(next.path("waitlistRank").asInt()).isEqualTo(2);
        assertThat(call("GET","/me/activities",null,"m1","MEMBER",200).path("mine").get(0).path("waitlistRank").isNull()).as("promoted").isTrue();
        assertThat(SnapshotSchemas.schema("ActivityRegistrationListItem").at("/properties/waitlistRank/description").asText()).contains("1-based","position");
    }

    /** Step 5 (E4-W05 question 3): `appliedFilters` of D7's registrations are the query's filters, without the path's activityId. */
    @Test void T_07_21_theRegistrationsListAppliesOnlyTheFiltersOfTheQuery() throws Exception {
        String id=published(1,false).path("id").asText(), other=published(1,false).path("id").asText();
        register(id,"m0",false,201); register(id,"m1",true,201); register(other,"m2",false,201);
        var all=call("GET","/activities/"+id+"/registrations",null,"admin","ADMIN",200);
        assertThat(all.path("appliedFilters")).as(all.path("appliedFilters").toString()).isEmpty();
        assertThat(all.path("totalItems").asInt()).isEqualTo(2);
        var waiting=call("GET","/activities/"+id+"/registrations?filter=state:eq:WAITLISTED",null,"instructor","INSTRUCTOR",200);
        assertThat(waiting.path("items")).hasSize(1);
        assertThat(waiting.path("appliedFilters")).hasSize(1);
        assertThat(waiting.at("/appliedFilters/0/field").asText()).isEqualTo("state");
        assertThat(waiting.at("/appliedFilters/0/op").asText()).isEqualTo("eq");
        assertThat(waiting.at("/appliedFilters/0/value").asText()).isEqualTo("WAITLISTED");
        // activityId comes from the path only: it is not one of the list's x-filterable fields.
        assertThat(call("GET","/activities/"+id+"/registrations?filter=activityId:eq:"+other,null,"admin","ADMIN",400).path("code").asText()).isEqualTo("INVALID_FILTER");
        assertThat(strings(openApi().at("/paths/~1api~1v1~1activities~1{id}~1registrations/get/x-filterable"))).doesNotContain("activityId");
    }

    /**
     * E5-T22 step 3 (review E5-T20 #5, R-07-08): `GET /me/activities` reads the waiting entries once for all the listed
     * activities, not once per waiting row, and each waiting row still reads its own rank. So a member waiting on three
     * activities costs as many `find` commands on `activity_registrations` (the driver's command metrics) as one waiting on one.
     */
    @Test void R_07_08_theMemberViewReadsTheWaitingEntriesOnceForAllItsActivities() throws Exception {
        var ids=new ArrayList<String>(); int next=0;
        for(int i=0;i<3;i++) {
            String id=published(1,false).path("id").asText(); ids.add(id);
            register(id,"m"+next++,false,201); for(int ahead=0;ahead<i;ahead++) register(id,"m"+next++,true,201);
            register(id,"m9",true,201);
        }
        String single=published(1,false).path("id").asText(); register(single,"m6",false,201); register(single,"m8",true,201);
        long three=finds(() -> {
            var mine=call("GET","/me/activities",null,"m9","MEMBER",200).path("mine");
            assertThat(mine).hasSize(3);
            for(int i=0;i<3;i++) assertThat(find(mine,"activityId",ids.get(i)).path("waitlistRank").asInt()).as("rank on activity "+i).isEqualTo(i+1);
        });
        long one=finds(() -> assertThat(call("GET","/me/activities",null,"m8","MEMBER",200).at("/mine/0/waitlistRank").asInt()).isEqualTo(1));
        assertThat(one).as("the member view reads activity_registrations").isPositive();
        assertThat(three).as("reads of activity_registrations: 3 waiting rows vs 1").isEqualTo(one);
    }

    /**
     * E5-T22 step 5 (review E5-T20 #4): both D7 lists accept `filter=id`, so their `x-filterable` publishes it (the query's
     * `activityId` stays refused on the registrations, where the path gives it).
     */
    @Test void T_07_21_idIsAFilterBothD7ListsPublish() throws Exception {
        String id=published(1,false).path("id").asText(), other=published(1,false).path("id").asText();
        var kept=register(id,"m0",false,201); register(id,"m1",true,201);
        var activities=call("GET","/activities?filter=id:eq:"+id,null,"instructor","INSTRUCTOR",200);
        assertThat(activities.path("items").findValuesAsText("id")).containsExactly(id);
        assertThat(activities.path("appliedFilters").findValuesAsText("field")).contains("id");
        assertThat(call("GET","/activities",null,"admin","ADMIN",200).path("items").findValuesAsText("id")).contains(id,other);
        var registrations=call("GET","/activities/"+id+"/registrations?filter=id:eq:"+kept.path("id").asText(),null,"admin","ADMIN",200);
        assertThat(registrations.path("items").findValuesAsText("registrationId")).containsExactly(kept.path("id").asText());
        assertThat(registrations.path("appliedFilters").findValuesAsText("field")).containsExactly("id");
        for(var api:List.of(openApi(),mapper.readTree(java.nio.file.Path.of("docs/openapi/openapi.json").toFile()))) {
            assertThat(strings(api.at("/paths/~1api~1v1~1activities/get/x-filterable"))).contains("id");
            assertThat(strings(api.at("/paths/~1api~1v1~1activities~1{id}~1registrations/get/x-filterable"))).contains("id").doesNotContain("activityId");
        }
    }

    interface Call { void run() throws Exception; }
    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry meters;
    /** The `find` commands `call` sends to `activity_registrations`. */
    long finds(Call call) throws Exception { long before=findCount(); call.run(); return findCount()-before; }
    long findCount() {
        return meters.find("mongodb.driver.commands").tag("collection","activity_registrations").tag("command","find").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    JsonNode openApi() throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    static List<String> strings(JsonNode values) { var result=new ArrayList<String>(); values.forEach(value -> result.add(value.asText())); return result; }
    static JsonNode find(JsonNode rows,String field,String value) {
        for(var row:rows) if(row.path(field).asText().equals(value)) return row;
        throw new AssertionError(field+"="+value+" is not in "+rows);
    }
}
