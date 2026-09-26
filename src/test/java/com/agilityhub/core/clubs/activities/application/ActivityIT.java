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

class ActivityIT extends ActivityFixtures {
    @Test @AuditCovers({AuditAction.ACTIVITY_PUBLISHED,AuditAction.ACTIVITY_UPDATED})
    void T_07_10_publicationCreatesFiveBlocksAndTitleCellsWithAtomicReplay() throws Exception {
        var a=ready(40,true); String id=a.path("id").asText(),key=UUID.randomUUID().toString();
        var request=auth(post("/api/v1/activities/"+id+"/publication").contentType("application/json").content("{}").header("Idempotency-Key",key),"admin","ADMIN");
        String first=mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        assertThat(count("ring_blocks","state","ACTIVE")).isEqualTo(5); assertThat(count("domain_events","type","ActivityPublished")).isEqualTo(1);
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("RingBlockCreated")),Document.class,"domain_events")).hasSize(5)
                .allSatisfy(e -> assertThat(e.get("payload",Document.class)).containsEntry("activityId",id).containsEntry("reason","ACTIVITY"));
        String week=call("POST","/weeks",Map.of("startDate","2026-09-14"),"admin","ADMIN",201).path("id").asText();
        assertThat(call("GET","/weeks/"+week+"/calendar",null,"admin","ADMIN",200).path("ringBlocks")).hasSize(5)
                .allSatisfy(block -> assertThat(block.path("activityTitle").asText()).isEqualTo("Activitat exemple"));
        var grid=call("GET","/day-grid?date=2026-09-15&view=member",null,"m0","MEMBER",200);
        assertThat(grid.toString()).contains("ACTIVITY","Activitat exemple");
        assertThat(call("POST","/activities/"+id+"/publication",Map.of(),"admin","ADMIN",409).path("code").asText()).isEqualTo("INVALID_STATE");
        assertThat(count("audit_entries","action","ACTIVITY_PUBLISHED")).isEqualTo(1); assertThat(count("audit_entries","action","ACTIVITY_UPDATED")).isGreaterThan(0);
    }
    @Test void T_07_11_conflictsRollbackPublicationAndExplicitClassCancellationSucceeds() throws Exception {
        var a=ready(5,true);String id=a.path("id").asText();
        mongo.save(new Document("_id","s07-class").append("clubId",CLUB).append("date","2026-09-15").append("startTime","18:00").append("endTime","19:00")
                .append("startsAt",Date.from(Instant.parse("2026-09-15T16:00:00Z"))).append("endsAt",Date.from(Instant.parse("2026-09-15T17:00:00Z")))
                .append("ringId","s07-ring-0").append("state","ACTIVE").append("levelIds",List.of("s07-D")).append("instructorIds",List.of())
                .append("capacity",5).append("capacityMode","MANUAL").append("counters",new Document("booked",3).append("waiting",0)).append("version",0L),"class_sessions");
        scheduling.bookings.put("s07-class",java.util.stream.IntStream.range(0,3).mapToObj(i -> new com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort.BookingRef("booking-"+i,"m"+i,"dog-m"+i,false)).toList());
        call("POST","/activities/"+id+"/publication",Map.of("cancelClasses",true,"cancelBookings",true,"adminText","Example activity"),"instructor","INSTRUCTOR",403);
        assertThat(call("POST","/activities/"+id+"/publication",Map.of(),"admin","ADMIN",409).path("code").asText()).isEqualTo("RING_BLOCK_CONFLICT");
        assertThat(count("ring_blocks","state","ACTIVE")).isZero();
        assertThat(call("GET","/activities/"+id+"/ring-conflicts",null,"admin","ADMIN",200).path("conflicts")).hasSize(1);
        call("POST","/activities/"+id+"/publication",Map.of("cancelClasses",true),"admin","ADMIN",422);
        scheduling.training.add(new com.agilityhub.core.clubs.scheduling.application.ports.TrainingConflictPort.Booking("training","s07-ring-1",Instant.parse("2026-09-15T16:00:00Z"),Instant.parse("2026-09-15T17:00:00Z"),"Example m0","Example dog"));
        call("POST","/activities/"+id+"/publication",Map.of("cancelClasses",true,"adminText","Example activity"),"admin","ADMIN",422);
        assertThat(count("ring_blocks","state","ACTIVE")).isZero();
        call("POST","/activities/"+id+"/publication",Map.of("cancelClasses",true,"cancelBookings",true,"adminText","Example activity"),"admin","ADMIN",200);
        dispatch();assertThat(count("notifications","code","N-08a")).isEqualTo(10);
        assertThat(count("class_sessions","state","CANCELLED")).isEqualTo(1); assertThat(count("domain_events","type","ClassCancelledByClub")).isEqualTo(1);
    }
    @Test void T_07_12_editAndUnpublishSynchronizeBlocksAndPermanentlyLockSlug() throws Exception {
        var a=published(5,true); String id=a.path("id").asText();
        var edited=call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"ringIds",List.of("s07-ring-0"),"startTime","18:30"),"admin","ADMIN",200);
        assertThat(count("ring_blocks","state","ACTIVE")).isEqualTo(1); assertThat(count("domain_events","type","RingBlockUpdated")).isEqualTo(1);
        String block=edited.path("ringBlockIds").get(0).asText();
        assertThat(call("POST","/ring-blocks/"+block+"/cancellation",Map.of(),"instructor","INSTRUCTOR",422).path("code").asText()).isEqualTo("RING_BLOCK_MANAGED_BY_ACTIVITY");
        var draft=call("DELETE","/activities/"+id+"/publication",null,"admin","ADMIN",200); assertThat(count("ring_blocks","state","ACTIVE")).isZero();
        assertThat(call("PATCH","/activities/"+id,Map.of("version",draft.path("version").asLong(),"slug","new-slug"),"admin","ADMIN",409).path("code").asText()).isEqualTo("SLUG_LOCKED");
    }
    @Test @AuditCovers(AuditAction.ACTIVITY_CANCELLED)
    void T_07_13_cancellationNotifiesActiveAndWaitingAndZeroesCounters() throws Exception {
        var a=published(22,true);String id=a.path("id").asText(); for(int i=0;i<25;i++) register(id,"m"+i,true,201);
        assertThat(call("GET","/activities/"+id+"/cancellation-preview",null,"admin","ADMIN",200).path("registrations")).hasSize(25);
        call("POST","/activities/"+id+"/cancellation",Map.of("reason","CLUB_MANUAL"),"admin","ADMIN",422);
        var cancelled=call("POST","/activities/"+id+"/cancellation",Map.of("reason","CLUB_MANUAL","adminText","Heavy rain"),"admin","ADMIN",200);
        assertThat(cancelled.path("counters").path("active").asInt()).isZero(); assertThat(count("activity_registrations","state","CANCELLED")).isEqualTo(25);
        assertThat(count("ring_blocks","state","ACTIVE")).isZero(); dispatch();
        assertThat(count("notifications","code","N-32c")).isEqualTo(75); assertThat(count("audit_entries","action","ACTIVITY_CANCELLED")).isEqualTo(1);
        dispatch(); assertThat(count("notifications","code","N-32c")).isEqualTo(75);
    }
    @Test void T_07_14_registrationIsPerPersonWithExplicitWaitlistAndReplay() throws Exception {
        String id=published(1,false).path("id").asText(); String key=UUID.randomUUID().toString();
        var request=auth(post("/api/v1/activity-registrations").header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("activityId",id))),"m0","MEMBER");
        String first=mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        assertThat(register(id,"m1",false,409).path("details").path("waitlistAvailable").asBoolean()).isTrue();
        assertThat(register(id,"m1",true,201).path("position").asInt()).isEqualTo(1);
        assertThat(register(id,"m0",false,409).path("code").asText()).isEqualTo("ALREADY_REGISTERED");
        try(var tenant=TenantContext.open(CLUB)) { assertThat(activities.require(id).counters()).isEqualTo(new Activity.Counters(1,1)); }
        dispatch();
        assertThat(changes("m0")).singleElement().satisfies(p -> assertThat(p).containsEntry("state","ACTIVE").containsEntry("origin","APP"));
        assertThat(changes("m1")).singleElement().satisfies(p -> assertThat(p).containsEntry("state","WAITLISTED").containsEntry("origin","APP"));
        assertThat(notices("m0","N-32b")).singleElement().satisfies(n -> assertThat(n.get("variables",Document.class)).containsEntry("state","ACTIVE"));
        assertThat(notices("m1","N-32b")).singleElement().satisfies(n -> assertThat(n.get("variables",Document.class)).containsEntry("state","WAITLISTED"));
    }
    @Test void T_07_15_cancellationAndCapacityGrowthPromoteFifoOnlyWithinDeadline() throws Exception {
        String id=published(1,false).path("id").asText(); var first=register(id,"m0",false,201); for(int i=1;i<4;i++) register(id,"m"+i,true,201);
        call("POST","/activity-registrations/"+first.path("id").asText()+"/cancellation",Map.of(),"m0","MEMBER",200);
        try(var tenant=TenantContext.open(CLUB)) {
            assertThat(registrations.live(id).stream().filter(r -> r.state()==RegistrationState.ACTIVE).map(ActivityRegistration::memberId)).containsExactly("m1");
            var promoted=registrations.forMember("m1").getFirst(); assertThat(promoted.promotedAt()).isEqualTo(clock.instant()); assertThat(promoted.position()).isNull();
            assertThat(activities.require(id).counters()).isEqualTo(new Activity.Counters(1,2));
        }
        dispatch();
        assertThat(changes("m1")).filteredOn(p -> Boolean.TRUE.equals(p.get("promoted"))).singleElement().satisfies(p -> assertThat(p).containsEntry("state","ACTIVE").containsEntry("origin","SYSTEM"));
        assertThat(notices("m1","N-32b")).extracting(n -> n.get("variables",Document.class).getString("state")).containsExactlyInAnyOrder("WAITLISTED","ACTIVE");
        assertThat(notices("m1","N-32b")).extracting(n -> n.getString("channel")).containsOnly("APP");
        var a=call("GET","/activities/"+id,null,"admin","ADMIN",200);
        a=call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"maxPlaces",3),"admin","ADMIN",200); assertThat(a.path("counters").path("waiting").asInt()).isZero();
        call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"maxPlaces",2),"admin","ADMIN",422);
        clock.setInstant(Instant.parse("2026-09-15T16:00:00Z"));
        String active;try(var tenant=TenantContext.open(CLUB)) { active=registrations.live(id).getFirst().id(); }
        call("POST","/activity-registrations/"+active+"/cancellation",Map.of(),"m1","MEMBER",422);
        // REGISTRATION_CLOSE: past registrationClosesAt only the club (impersonating) can cancel, and the freed seat is not promoted.
        clock.setInstant(Instant.parse("2026-09-14T06:00:00Z")); parameter("activities.cancelDeadline","REGISTRATION_CLOSE","enum");
        var b=ready(1,false); String second=b.path("id").asText();
        call("PATCH","/activities/"+second,Map.of("version",b.path("version").asLong(),"registrationTo","2026-09-14"),"admin","ADMIN",200);
        call("POST","/activities/"+second+"/publication",Map.of(),"admin","ADMIN",200);
        var held=register(second,"m4",false,201); register(second,"m5",true,201);
        clock.setInstant(Instant.parse("2026-09-15T06:00:00Z"));
        String path="/activity-registrations/"+held.path("id").asText()+"/cancellation";
        assertThat(call("POST",path,Map.of(),"m4","MEMBER",422).path("code").asText()).isEqualTo("REGISTRATION_NOT_CANCELLABLE");
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try(var tenant=TenantContext.open(CLUB)) { issued=impersonations.create("s07-admin","m4","Example request"); }
        mvc.perform(post("/api/v1"+path).header("Host",HOST).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("reason","Member requested")))
                .with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isOk());
        try(var tenant=TenantContext.open(CLUB)) {
            assertThat(registrations.forMember("m5").getFirst().state()).isEqualTo(RegistrationState.WAITLISTED);
            assertThat(activities.require(second).counters()).isEqualTo(new Activity.Counters(0,1));
        }
    }
    @Test void T_07_15_anImpersonatedWaitlistedRegistrationIsPromotedBySystemWithN32bInAppOnly() throws Exception {
        String id=published(1,false).path("id").asText(); var first=register(id,"m0",false,201);
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try(var tenant=TenantContext.open(CLUB)) { issued=impersonations.create("s07-admin","m1","Example request"); }
        var waiting=mapper.readTree(mvc.perform(post("/api/v1/activity-registrations").header("Host",HOST).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("activityId",id,"joinWaitlist",true)))
                .with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(waiting.path("state").asText()).isEqualTo("WAITLISTED"); assertThat(waiting.path("origin").asText()).isEqualTo("BACKOFFICE");
        dispatch();
        assertThat(notices("m1","N-32b")).as("the club's own change: every channel").extracting(n -> n.getString("channel")).contains("APP","EMAIL");
        call("POST","/activity-registrations/"+first.path("id").asText()+"/cancellation",Map.of(),"m0","MEMBER",200); dispatch();
        try(var tenant=TenantContext.open(CLUB)) {
            var promoted=registrations.forMember("m1").getFirst();
            assertThat(promoted.state()).isEqualTo(RegistrationState.ACTIVE); assertThat(promoted.origin()).as("stored origin kept").isEqualTo(RegistrationOrigin.BACKOFFICE);
        }
        assertThat(changes("m1")).filteredOn(p -> Boolean.TRUE.equals(p.get("promoted"))).singleElement().satisfies(p -> assertThat(p).containsEntry("origin","SYSTEM"));
        var envelope=mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("ActivityRegistrationChanged").and("payload.memberId").is("m1")
                .and("payload.promoted").is(true)),Document.class,"domain_events");
        assertThat(envelope).containsEntry("origin","SYSTEM"); assertThat(envelope.get("actorAccountId")).isNull(); assertThat(envelope.get("impersonatedMemberId")).isNull();
        String promotion=envelope.getString("_id"); // notification ids are eventId:memberId:channel
        assertThat(notices("m1","N-32b")).filteredOn(n -> n.getString("_id").contains(promotion))
                .as("the promotion is the system's: APP only").extracting(n -> n.getString("channel")).containsExactly("APP");
    }
    @Test void T_07_16_memberViewsFilterSelectedDogAndShowOwnRegistrationAndHistory() throws Exception {
        String id=published(4,false).path("id").asText();
        assertThat(call("GET","/me/activities?dogId=dog-m0",null,"m0","MEMBER",200).path("bookable")).hasSize(1);
        call("GET","/me/activities?dogId=dog-m1",null,"m0","MEMBER",404);
        register(id,"m0",false,201); assertThat(call("GET","/me/activities/"+id,null,"m0","MEMBER",200).path("myRegistration").path("state").asText()).isEqualTo("ACTIVE");
        try(var tenant=TenantContext.open(CLUB)) { assertThat(queries.liveRegistrationsFor("m0").getFirst()).containsEntry("dogId",null).containsEntry("state","REGISTERED"); }
        String draft=create().path("id").asText(); call("GET","/me/activities/"+draft,null,"m0","MEMBER",404);
    }
    @Test void T_07_17_publicApiIsLocalizedKeyScopedAndContainsNoPersonalData() throws Exception {
        String id=published(5,false).path("id").asText();register(id,"m0",false,201);
        mvc.perform(get("/api/v1/public/"+CLUB+"/activities")).andExpect(status().isForbidden());
        var response=mvc.perform(get("/api/v1/public/"+CLUB+"/activities").header("X-Api-Key",KEY).header("Accept-Language","es")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","public, max-age=300")).andExpect(header().exists("ETag")).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("Actividad ejemplo","titleI18n").doesNotContain("memberId","registrations","phones","example.test\"","Example m0");
        assertThat(mapper.readTree(response.getContentAsString()).path("items").get(0).path("places").path("free").asInt()).isEqualTo(4);
        mvc.perform(get("/api/v1/public/"+OTHER+"/activities").header("X-Api-Key",KEY)).andExpect(status().isForbidden());
        String slug=create().path("slug").asText();mvc.perform(get("/api/v1/public/"+CLUB+"/activities/"+slug).header("X-Api-Key",KEY)).andExpect(status().isNotFound());
    }
    @Test void T_07_18_finishEndedIsStrictIdempotentAndProducesHistory() throws Exception {
        String id=published(5,false).path("id").asText();register(id,"m0",false,201);
        try(var tenant=TenantContext.open(CLUB)) {
            var end=activities.require(id).endsAt();assertThat(lifecycle.finishEnded(end)).isZero();clock.setInstant(end.plusSeconds(1));
            assertThat(lifecycle.finishEnded(clock.instant())).isEqualTo(1);assertThat(lifecycle.finishEnded(clock.instant())).isZero();
            assertThat(queries.historyRowsFor("m0",end.minusSeconds(86400),clock.instant()).getFirst()).containsEntry("state","DONE");
        }
    }
    @Test void T_07_19_systemCancellationHonorsIntervalsAndMemberLeftIsSilent() throws Exception {
        String id=published(1,false).path("id").asText();register(id,"m0",false,201);register(id,"m1",true,201);
        try(var tenant=TenantContext.open(CLUB)) {
            assertThat(registrationService.cancelForInactivity("m0",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31))).isZero();
            assertThat(registrationService.cancelForInactivity("m0",LocalDate.of(2026,9,1),LocalDate.of(2026,9,30))).isEqualTo(1);
            assertThat(registrations.forMember("m1").getFirst().state()).isEqualTo(RegistrationState.ACTIVE);
            dispatch();
            assertThat(changes("m0")).anySatisfy(p -> assertThat(p).containsEntry("state","CANCELLED").containsEntry("cancelReason","INACTIVITY"));
            assertThat(notices("m0","N-32b")).extracting(n -> n.get("variables",Document.class).getString("state")).containsExactlyInAnyOrder("ACTIVE","CANCELLED");
            long before=count("notifications","code","N-32b");
            mongo.updateFirst(Query.query(Criteria.where("_id").is("m1")),new Update().set("status","LEFT"),"members");
            transactions.write(List.of(),() -> { events.publish(new com.agilityhub.core.shared.domain.events.MemberStatusChanged(CLUB,"m1",clock.instant(),Map.of("memberId","m1","before","ACTIVE","after","LEFT","effectiveDate","2026-09-14"),"s07-admin",null,DomainEvent.Origin.BACKOFFICE));return null; });
            dispatch();
            assertThat(registrations.forMember("m1").getFirst().cancelReason()).isEqualTo(RegistrationCancelReason.MEMBER_LEFT);
            assertThat(changes("m1")).anySatisfy(p -> assertThat(p).containsEntry("state","CANCELLED").containsEntry("cancelReason","MEMBER_LEFT"));
            assertThat(count("notifications","code","N-32b")).isEqualTo(before);
            assertThat(notices("m1","N-32b")).extracting(n -> n.get("variables",Document.class).getString("state")).doesNotContain("CANCELLED");
        }
    }
    @Test void T_07_20_htmlAndUploadPurposesRejectInvalidInputs() throws Exception {
        var a=create();String id=a.path("id").asText();
        a=call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"longDescription",Map.of("ca","<p>Hello<script>bad()</script><b>world</b></p>")),"admin","ADMIN",200);
        assertThat(a.path("longDescriptionHtml").asText()).contains("<strong>world</strong>").doesNotContain("script");
        for(var input:List.of(Map.of("purpose","ACTIVITY_IMAGE","fileName","video.mp4","mimeType","video/mp4","sizeBytes",4),Map.of("purpose","ACTIVITY_DOCUMENT","fileName","huge.pdf","mimeType","application/pdf","sizeBytes",31*1024*1024)))
            call("POST","/attachments/upload-url",input,"admin","ADMIN",400);
    }
    String upload(String purpose,String type) throws Exception {
        var grant=call("POST","/attachments/upload-url",Map.of("purpose",purpose,"fileName","example","mimeType",type,"sizeBytes",4),"admin","ADMIN",201);
        mvc.perform(auth(put(java.net.URI.create(grant.path("uploadUrl").asText())).contentType(type).content(new byte[]{1,2,3,4}),"admin","ADMIN")).andExpect(status().isNoContent());
        return grant.path("fileKey").asText();
    }
    @Test void T_07_20_filesAreClaimedLimitedAndPublicDownloadsNeedNoApiKey() throws Exception {
        var a=published(3,false);String id=a.path("id").asText(),slug=a.path("slug").asText();
        String image=upload("ACTIVITY_IMAGE","image/png");
        call("PUT","/activities/"+id+"/image",Map.of("fileKey",image,"name","Example image"),"admin","ADMIN",200);
        String path="/api/v1/public/"+CLUB+"/activities/"+slug+"/files/"+image;
        var redirect=mvc.perform(get(path)).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        mvc.perform(get(java.net.URI.create(redirect))).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2,3,4}));
        mvc.perform(get(java.net.URI.create(redirect.replace("signature=","signature=invalid")))).andExpect(status().isForbidden());
        String first=null;
        for(int i=0;i<11;i++) {
            String key=upload("ACTIVITY_DOCUMENT","application/pdf");if(i==0) first=key;
            call("POST","/activities/"+id+"/documents",Map.of("fileKey",key,"name","Example document "+i),"admin","ADMIN",i<10?201:422);
        }
        call("DELETE","/activities/"+id+"/documents/"+first,null,"admin","ADMIN",204);
        call("DELETE","/activities/"+id+"/image",null,"admin","ADMIN",204);
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }
    @Test void T_07_21_universalListsFilterSortAndExportWithoutLeakingFields() throws Exception {
        String id=published(5,false).path("id").asText();register(id,"m0",false,201);
        assertThat(call("GET","/activities?filter=registrationOpen:eq:true",null,"admin","ADMIN",200).path("totalItems").asInt()).isEqualTo(1);
        assertThat(call("GET","/activities/"+id+"/registrations?sort=memberLastName,asc",null,"instructor","INSTRUCTOR",200).path("items").get(0).path("member").path("fullName").asText()).isEqualTo("Example m0");
        call("GET","/activities?filter=secret:eq:x",null,"admin","ADMIN",400);
        call("GET","/activities/filter-values?field=state",null,"admin","ADMIN",200);
        mvc.perform(auth(get("/api/v1/activity-registrations/export").param("format","xlsx").param("filter","activityId:eq:"+id),"admin","ADMIN")).andExpect(status().isOk()).andExpect(header().exists("Content-Disposition"));
    }
    @Test void T_07_22_staffMemberAndCrossTenantGuardsPreserveOwnership() throws Exception {
        String id=published(4,false).path("id").asText();var r=register(id,"m0",false,201);
        for(String role:List.of("ADMIN","INSTRUCTOR")) call("POST","/activity-registrations",Map.of("activityId",id),role.toLowerCase(),role,403);
        call("GET","/activity-registrations/export?format=xlsx",null,"instructor","INSTRUCTOR",403);
        call("GET","/activities",null,"m0","MEMBER",403);call("GET","/activities/"+id,null,"instructor","INSTRUCTOR",200);
        call("PATCH","/activities/"+id,Map.of("version",2),"instructor","INSTRUCTOR",403);
        call("GET","/activity-registrations/"+r.path("id").asText(),null,"m1","MEMBER",404);
        mvc.perform(get("/api/v1/activities/"+id).header("Host","s07-b.example.test").with(jwt().jwt(j -> j.subject("foreign").claim("clubId",OTHER)).authorities(() -> "ROLE_ADMIN"))).andExpect(status().isNotFound());
    }
    @Test @AuditCovers({AuditAction.ACTIVITY_REGISTERED_BY_CLUB,AuditAction.ACTIVITY_REGISTRATION_CANCELLED_BY_CLUB})
    void T_07_23_impersonationRecordsBothActorsAndClubChangeChannels() throws Exception {
        String id=published(3,false).path("id").asText();com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try(var tenant=TenantContext.open(CLUB)) { issued=impersonations.create("s07-admin","m0","Example request"); }
        var request=post("/api/v1/activity-registrations").header("Host",HOST).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("activityId",id)))
                .with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"));
        var r=mapper.readTree(mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());assertThat(r.path("origin").asText()).isEqualTo("BACKOFFICE");
        String path="/api/v1/activity-registrations/"+r.path("id").asText()+"/cancellation";
        mvc.perform(post(path).header("Host",HOST).contentType("application/json").content("{}").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Host",HOST).contentType("application/json").content("{\"reason\":\"Member requested\"}").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"))).andExpect(status().isOk());
        assertThat(count("audit_entries","action","ACTIVITY_REGISTERED_BY_CLUB")).isEqualTo(1);assertThat(count("audit_entries","action","ACTIVITY_REGISTRATION_CANCELLED_BY_CLUB")).isEqualTo(1);
        var audit=mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("ACTIVITY_REGISTRATION_CANCELLED_BY_CLUB")),Document.class,"audit_entries");
        assertThat(audit).containsEntry("actorAccountId","s07-admin").containsEntry("impersonatedMemberId","m0").containsEntry("reason","Member requested");
        // E5-T07: deterministic now that each test starts with an empty outbox backlog (AbstractIntegrationTest).
        dispatch(); assertThat(count("notifications","code","N-32b")).isEqualTo(6);
        String second=published(3,false).path("id").asText();var self=register(second,"m0",false,201);
        mvc.perform(post("/api/v1/activity-registrations/"+self.path("id").asText()+"/cancellation").header("Host",HOST).contentType("application/json")
                .content("{\"reason\":\"Member requested\"}").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.origin").value("BACKOFFICE"));
    }
    @Test void T_07_24_twentyConcurrentRequestsSerializeTheLastSeatAndUniqueFifoPositions() throws Exception {
        String id=published(1,false).path("id").asText();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(20)) {
            var futures=new ArrayList<Future<JsonNode>>();
            for(int i=0;i<20;i++) {String member="m"+i;futures.add(pool.submit(() -> {start.await();return register(id,member,true,201);}));}
            start.countDown(); for(var future:futures) future.get(60,TimeUnit.SECONDS);
        }
        try(var tenant=TenantContext.open(CLUB)) {
            assertThat(activities.require(id).counters()).isEqualTo(new Activity.Counters(1,19));
            assertThat(registrations.live(id).stream().filter(r -> r.state()==RegistrationState.WAITLISTED).map(ActivityRegistration::position).sorted()).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,19).boxed().toList());
        }
    }
    @Test void T_07_24_withoutWaitlistOnlyOneOfTwentyRequestsGetsTheLastSeat() throws Exception {
        String id=published(1,false).path("id").asText();var enabled=EnumSet.allOf(Module.class);enabled.remove(Module.WAITLIST);modules(enabled);
        var start=new CountDownLatch(1);var statuses=new ArrayList<Integer>();
        try(var pool=Executors.newFixedThreadPool(20)) {
            var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<20;i++) {String member="m"+i;futures.add(pool.submit(() -> {
                start.await();return mvc.perform(auth(post("/api/v1/activity-registrations").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                    .content(mapper.writeValueAsBytes(Map.of("activityId",id,"joinWaitlist",true))),member,"MEMBER")).andReturn().getResponse().getStatus();
            }));}
            start.countDown();for(var future:futures) statuses.add(future.get(60,TimeUnit.SECONDS));
        }
        assertThat(statuses.stream().filter(status -> status==201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status==409).count()).isEqualTo(19);
        try(var tenant=TenantContext.open(CLUB)) { assertThat(activities.require(id).counters()).isEqualTo(new Activity.Counters(1,0));assertThat(registrations.live(id)).hasSize(1); }
    }
    @Test void T_07_25_concurrentCancellationsPromoteOnlyOnceAndReplayOneResponse() throws Exception {
        String id=published(2,false).path("id").asText();var one=register(id,"m0",false,201);var two=register(id,"m1",false,201);register(id,"m2",true,201);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var request=auth(post("/api/v1/activity-registrations/"+one.path("id").asText()+"/cancellation").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}"),"m0","MEMBER");
            var first=pool.submit(() -> mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var second=pool.submit(() -> call("POST","/activity-registrations/"+two.path("id").asText()+"/cancellation",Map.of(),"m1","MEMBER",200));
            String response=first.get();second.get();mvc.perform(request).andExpect(status().isOk()).andExpect(content().string(response));
        }
        try(var tenant=TenantContext.open(CLUB)) { assertThat(activities.require(id).counters()).isEqualTo(new Activity.Counters(1,0));assertThat(registrations.live(id)).hasSize(1); }
    }
    @Test void T_07_26_publicationAndInstructorBlockCannotBothClaimTheSameWindow() throws Exception {
        String id=ready(3,true).path("id").asText();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var publication=pool.submit(() -> {start.await();return mvc.perform(auth(post("/api/v1/activities/"+id+"/publication").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}"),"admin","ADMIN")).andReturn().getResponse().getStatus();});
            var block=pool.submit(() -> {start.await();return mvc.perform(auth(post("/api/v1/ring-blocks").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"ringId\":\"s07-ring-0\",\"from\":\"2026-09-15T16:00:00Z\",\"to\":\"2026-09-15T18:00:00Z\",\"kind\":\"BLOCK\",\"reason\":\"MAINTENANCE\"}"),"instructor","INSTRUCTOR")).andReturn().getResponse().getStatus();});
            start.countDown();assertThat(List.of(publication.get(),block.get())).contains(409).anyMatch(status -> status>=200 && status<300);
        }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("ringId").is("s07-ring-0").and("state").is("ACTIVE")),"ring_blocks")).isEqualTo(1);
    }
    @Test void T_07_27_disabledModulesPreserveDataAndWaitlistOffReturnsFull() throws Exception {
        String id=published(1,true).path("id").asText();register(id,"m0",false,201);var enabled=EnumSet.allOf(Module.class);enabled.remove(Module.WAITLIST);modules(enabled);
        assertThat(register(id,"m1",true,409).path("details").path("waitlistAvailable").asBoolean()).isFalse();
        enabled.remove(Module.ACTIVITIES);modules(enabled);call("GET","/activities/"+id,null,"admin","ADMIN",404);
        mvc.perform(get("/api/v1/public/"+CLUB+"/activities").header("X-Api-Key",KEY)).andExpect(status().isNotFound());
        try(var tenant=TenantContext.open(CLUB)) { assertThat(queries.bookableFor("m0",null)).isEmpty();assertThat(queries.liveRegistrationsFor("m0")).isEmpty();assertThat(lifecycle.finishEnded(clock.instant().plusSeconds(999999))).isZero(); }
        assertThat(call("GET","/day-grid?date=2026-09-15&view=member",null,"m0","MEMBER",200).toString()).doesNotContain("ACTIVITY");
        modules(EnumSet.allOf(Module.class));call("GET","/activities/"+id,null,"admin","ADMIN",200);
    }
    @Test void T_07_28_notificationsUseAudienceRecipientLocaleAndGsmLimit() throws Exception {
        var a=ready(40,false);String id=a.path("id").asText();a=call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"levelIds",List.of("s07-D")),"admin","ADMIN",200);
        call("POST","/activities/"+id+"/publication",Map.of("notifyEmail",true),"admin","ADMIN",200);dispatch();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-32a").and("accountId").is("s07-m31")),"notifications")).isZero();
        assertThat(count("notifications","code","N-32a")).isEqualTo(66);
        register(id,"m1",false,201); var current=call("GET","/activities/"+id,null,"admin","ADMIN",200);
        call("PATCH","/activities/"+id,Map.of("version",current.path("version").asLong(),"startTime","18:30"),"admin","ADMIN",200);dispatch();assertThat(count("notifications","code","N-32d")).isEqualTo(3);
        var enabled=EnumSet.allOf(Module.class);enabled.remove(Module.SMS);modules(enabled);
        assertThat(call("GET","/activities/"+id+"/cancellation-preview",null,"admin","ADMIN",200).path("registrations").get(0).path("channels").toString()).doesNotContain("SMS");
        call("POST","/activities/"+id+"/cancellation",Map.of("reason","CLUB_MANUAL","adminText","Pluja àèíòú 🙂 ".repeat(25)),"admin","ADMIN",200);dispatch();
        var sms=mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-32c").and("channel").is("SMS")),Document.class,"notifications");
        assertThat(sms).isNotNull();assertThat(sms.getString("status")).isEqualTo("SKIPPED_MODULE_OFF");assertThat(sms.getString("body")).hasSizeLessThanOrEqualTo(160).doesNotContain("🙂");
    }
    @Test void T_07_17_T_07_28_publicPastCacheAndNotificationPreferences() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("m0")),new Update().set("notificationPreferences",Map.of("CLUB_CHANGES",Map.of("email",false,"sms",false))),"members");
        var a=published(5,false);String id=a.path("id").asText();register(id,"m0",false,201);register(id,"m1",false,201);dispatch();
        mvc.perform(get("/api/v1/public/"+CLUB+"/activities").param("scope","invalid").header("X-Api-Key",KEY)).andExpect(status().isBadRequest());
        var path="/api/v1/public/"+CLUB+"/activities/"+a.path("slug").asText();
        mvc.perform(get(path).header("X-Api-Key",KEY)).andExpect(status().isOk());
        mvc.perform(get(path).header("X-Api-Key",KEY)).andExpect(status().isOk());
        a=call("GET","/activities/"+id,null,"admin","ADMIN",200);
        call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"date","2026-09-16","startTime","18:30","endTime","20:30","location",Map.of("atClub",false,"name","Example park")),"admin","ADMIN",200);dispatch();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("accountId").is("s07-m0").and("code").is("N-32d")),"notifications")).isEqualTo(1);
        assertThat(count("notifications","code","N-32d")).isEqualTo(4);
        var app=mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("accountId").is("s07-m1").and("code").is("N-32d").and("channel").is("APP")),Document.class,"notifications");
        assertThat(app.getString("locale")).isEqualTo("en");assertThat(app.get("variables",Document.class).getString("activity_title")).isEqualTo("Example activity");
        try(var tenant=TenantContext.open(CLUB)) {
            var end=activities.require(id).endsAt();clock.setInstant(end.plusSeconds(1));assertThat(lifecycle.finishEnded(clock.instant())).isEqualTo(1);
            assertThat(queries.liveRegistrationsFor("m0")).isEmpty();assertThat(queries.historyRowsFor("m0",Instant.EPOCH,clock.instant())).hasSize(1);
            assertThat(queries.historyRowsFor("m0",clock.instant(),clock.instant().plusSeconds(1))).isEmpty();
        }
        dispatch();mvc.perform(get("/api/v1/public/"+CLUB+"/activities").param("scope","past").header("X-Api-Key",KEY)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        assertThat(call("GET","/me/activities",null,"m0","MEMBER",200).path("mine")).isEmpty();
        call("GET","/me/activities/"+id,null,"m0","MEMBER",200);
    }
    List<Document> changes(String memberId) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("ActivityRegistrationChanged").and("payload.memberId").is(memberId)),Document.class,"domain_events")
                .stream().map(e -> e.get("payload",Document.class)).toList();
    }
    List<Document> notices(String member,String code) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("accountId").is("s07-"+member).and("code").is(code)),Document.class,"notifications");
    }
    @Test void T_07_17_apiKeyIsCheckedBeforeTheClubAndTheModule() throws Exception {
        String slug=published(5,false).path("slug").asText(); var enabled=EnumSet.allOf(Module.class); enabled.remove(Module.ACTIVITIES); modules(enabled);
        for(String path:List.of("/api/v1/public/"+CLUB+"/activities","/api/v1/public/"+CLUB+"/activities/"+slug,"/api/v1/public/missing-club/activities","/api/v1/public/missing-club/activities/"+slug)) {
            mvc.perform(get(path)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
            mvc.perform(get(path).header("X-Api-Key","wrong")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
        }
        // With the right key the module guard answers next; the key of another club never reaches it.
        mvc.perform(get("/api/v1/public/"+CLUB+"/activities").header("X-Api-Key",KEY)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        mvc.perform(get("/api/v1/public/"+OTHER+"/activities").header("X-Api-Key",KEY)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
        modules(EnumSet.allOf(Module.class));
        mvc.perform(get("/api/v1/public/"+CLUB+"/activities").header("X-Api-Key",KEY)).andExpect(status().isOk());
    }
    void parameter(String key,Object value,String type) {
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB).and("key").is(key)),Parameter.class);
        mongo.insert(new Parameter(CLUB+":"+key,CLUB,key,value,type,"club",null,List.of(),0L,clock.instant()));configs.invalidate(CLUB);publicApi.invalidate(CLUB);
    }
    @Test void T_07_03_T_07_12_patchValidatesNullsLocalesSlugsAndTerminalEdits() throws Exception {
        var a=ready(5,false);String id=a.path("id").asText();
        var patch=new LinkedHashMap<String,Object>();patch.put("version",a.path("version").asLong());
        patch.put("title",Map.of("ca","Fira exemple"));patch.put("type","OTHER");patch.put("typeLabel",Map.of("ca","Fira"));
        patch.put("shortDescription",Map.of("ca","Example description"));patch.put("longDescription",Map.of("ca","<p>Example description</p>"));
        patch.put("location",Map.of("atClub",false,"name","Example park","address","Example street"));patch.put("minPlaces",2);patch.put("levelIds",List.of("s07-D"));
        patch.put("slug","example-fair");patch.put("internalNotes","Private planning");patch.put("visibility","MEMBERS");patch.put("priceTiers",List.of());
        a=call("PATCH","/activities/"+id,patch,"admin","ADMIN",200);
        assertThat(a.path("typeDisplay").asText()).isEqualTo("Fira");assertThat(a.path("belowMinimum").asBoolean()).isTrue();
        assertThat(call("GET","/activities/"+id,null,"instructor","INSTRUCTOR",200).has("internalNotes")).isFalse();
        call("PATCH","/activities/"+id,patch,"admin","ADMIN",409);
        for(var bad:List.of(Map.of("title",Map.of("de","Wrong locale")),Map.of("unknown","x"),Map.of("priceTiers",List.of("paid")),Map.of("location",Map.of("atClub",false)),Map.of("type","NO_SUCH_TYPE"),Map.of("ringIds",Arrays.asList((String)null)),Map.of("levelIds",Arrays.asList((String)null)))) {
            var body=new LinkedHashMap<String,Object>(bad);body.put("version",a.path("version").asLong());
            call("PATCH","/activities/"+id,body,"admin","ADMIN",bad.containsKey("title")?422:400);
        }
        var other=create();call("PATCH","/activities/"+other.path("id").asText(),Map.of("version",other.path("version").asLong(),"slug","example-fair"),"admin","ADMIN",409);
        patch.clear();patch.put("version",a.path("version").asLong());for(String field:List.of("typeLabel","shortDescription","longDescription","maxPlaces","minPlaces","internalNotes","ringBlockWindow")) patch.put(field,null);
        a=call("PATCH","/activities/"+id,patch,"admin","ADMIN",200);assertThat(a.path("maxPlaces").isNull()).isTrue();
        call("POST","/activities/"+id+"/publication",Map.of(),"admin","ADMIN",200);
        register(id,"m0",false,201);call("DELETE","/activities/"+id+"/publication",null,"admin","ADMIN",422);
        a=call("POST","/activities/"+id+"/cancellation",Map.of("reason","DELETED","adminText","Example cancellation"),"admin","ADMIN",200);
        call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"title",Map.of("ca","Forbidden")),"admin","ADMIN",409);
        call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"internalNotes","Archive note"),"admin","ADMIN",200);
        call("DELETE","/activities/"+id+"/image",null,"admin","ADMIN",409);
        assertThat(call("GET","/activities?filter=deleted:eq:true",null,"admin","ADMIN",200).path("items")).hasSize(1);
    }
    @Test void T_07_16_T_07_27_queriesReflectBlocksLevelsAndCurrentTimezone() throws Exception {
        String id=published(1,false).path("id").asText();register(id,"m0",false,201);
        assertThat(call("GET","/me/activities",null,"m1","MEMBER",200).path("bookable").get(0).path("rowState").asText()).isEqualTo("FULL_WAITLIST");
        var enabled=EnumSet.allOf(Module.class);enabled.remove(Module.WAITLIST);enabled.remove(Module.COURSES);modules(enabled);
        assertThat(call("GET","/activities/"+id,null,"admin","ADMIN",200).has("placementIds")).isFalse();
        assertThat(call("GET","/me/activities",null,"m1","MEMBER",200).path("bookable").get(0).path("rowState").asText()).isEqualTo("FULL");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("m1")),new Update().set("bookingBlock",Map.of("active",true,"reason","Example block")),"members");
        assertThat(call("GET","/me/activities",null,"m1","MEMBER",200).path("bookable").get(0).path("rowState").asText()).isEqualTo("NOT_BOOKABLE");
        parameter("levels.enabled",false,"BOOLEAN");var a=call("GET","/activities/"+id,null,"admin","ADMIN",200);
        a=call("PATCH","/activities/"+id,Map.of("version",a.path("version").asLong(),"levelIds",List.of("missing")),"admin","ADMIN",200);assertThat(a.path("levelIds")).isEmpty();
        try(var tenant=TenantContext.open(CLUB)) { assertThat(audience.admittedMemberIds(activities.require(id))).hasSize(34); }
        clock.setInstant(Instant.parse("2026-09-15T22:30:00Z"));
        var tree=(ObjectNode)mapper.valueToTree(clubs.findById(CLUB).orElseThrow());tree.put("timeZone","America/Argentina/Buenos_Aires");clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(CLUB);
        assertThat(call("GET","/activities?filter=registrationOpen:eq:true",null,"admin","ADMIN",200).path("items")).hasSize(1);
        try(var tenant=TenantContext.open(CLUB)) { assertThat(lifecycle.finishEnded(clock.instant())).isZero(); }
    }

    // ---- E4-T06: the read models of D7 and the app (S07 §2 D7, R-07-11, R-07-13 and the «Canvis» of 24-09)
    JsonNode find(JsonNode rows,String field,String value) {
        for(var row:rows) if(row.path(field).asText().equals(value)) return row;
        throw new AssertionError(field+"="+value+" is not in "+rows);
    }
    JsonNode read(String path,String member,String role,String language) throws Exception {
        return mapper.readTree(mvc.perform(auth(get("/api/v1"+path),member,role).header("Accept-Language",language)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    static void assertNoEnd(JsonNode row,String field) {
        assertThat(row.has(field)).as(field+" is sent: "+row).isTrue();
        assertThat(row.path(field).isNull()).as("no made-up end: "+row).isTrue();
    }
    /** «Lliga exemple»: Saturday 19-09 from 9:00, no end, away from the club (a published activity with rings needs both hours, R-07-04). */
    JsonNode startOnly() throws Exception {
        var a=create(); var patch=new LinkedHashMap<String,Object>(); patch.put("version",a.path("version").asLong());
        patch.put("title",Map.of("ca","Lliga exemple","es","Liga ejemplo","en","Example league")); patch.put("type","SOCIAL_LEAGUE");
        patch.put("date","2026-09-19"); patch.put("startTime","09:00"); patch.put("location",Map.of("atClub",false,"name","Example park"));
        patch.put("registrationFrom","2026-09-01"); patch.put("registrationTo","2026-09-18");
        call("PATCH","/activities/"+a.path("id").asText(),patch,"admin","ADMIN",200);
        return call("POST","/activities/"+a.path("id").asText()+"/publication",Map.of(),"admin","ADMIN",200);
    }
    @Test void T_07_16_T_07_18_aStartOnlyActivityHasANullEndInEveryReadModel() throws Exception {
        var lliga=startOnly(); String id=lliga.path("id").asText(), ended=published(5,false).path("id").asText(); // 15-09 18:00–20:00
        // The model keeps its convention (the next day at 00:00 local) for ordering and the schedulers; no read model shows it.
        assertNoEnd(lliga,"endTime"); assertThat(lliga.path("endsAt").asText()).isEqualTo("2026-09-19T22:00:00Z");
        var bookable=call("GET","/me/activities",null,"m0","MEMBER",200).path("bookable");
        assertThat(find(bookable,"id",id).path("startsAtLocal").asText()).isEqualTo("2026-09-19T09:00"); assertNoEnd(find(bookable,"id",id),"endsAtLocal");
        assertThat(find(bookable,"id",ended).path("endsAtLocal").asText()).isEqualTo("2026-09-15T20:00");
        var registration=register(id,"m0",false,201); var other=register(ended,"m0",false,201);
        assertNoEnd(registration.path("activity"),"endsAtLocal"); assertThat(other.at("/activity/endsAtLocal").asText()).isEqualTo("2026-09-15T20:00");
        assertNoEnd(call("GET","/activity-registrations/"+registration.path("id").asText(),null,"m0","MEMBER",200).path("activity"),"endsAtLocal");
        var mine=call("GET","/me/activities",null,"m0","MEMBER",200).path("mine");
        assertNoEnd(find(mine,"activityId",id).path("activity"),"endsAtLocal");
        assertThat(find(mine,"activityId",ended).at("/activity/endsAtLocal").asText()).isEqualTo("2026-09-15T20:00");
        var detail=call("GET","/me/activities/"+id,null,"m0","MEMBER",200);
        assertNoEnd(detail,"endTime"); assertNoEnd(detail.path("myRegistration").path("activity"),"endsAtLocal");
        // 03: the ACTIVITY rows of GET /me/home.
        var home=call("GET","/me/home",null,"m0","MEMBER",200).path("reservations");
        assertNoEnd(find(home,"id",registration.path("id").asText()),"endsAtLocal");
        assertThat(find(home,"id",other.path("id").asText()).path("endsAtLocal").asText()).isEqualTo("2026-09-15T20:00");
        // D7: the list row.
        var list=call("GET","/activities",null,"admin","ADMIN",200).path("items");
        assertThat(find(list,"id",id).path("startTime").asText()).isEqualTo("09:00"); assertNoEnd(find(list,"id",id),"endTime");
        assertThat(find(list,"id",ended).path("endTime").asText()).isEqualTo("20:00");
        // The convention still decides: live on 03 until 00:00 local of the next day, then finished and on 25, whose row has no end.
        clock.setInstant(Instant.parse("2026-09-19T21:59:00Z"));
        assertThat(call("GET","/me/home",null,"m0","MEMBER",200).path("reservations")).extracting(r -> r.path("id").asText()).contains(registration.path("id").asText());
        clock.setInstant(Instant.parse("2026-09-19T22:00:01Z"));
        assertThat(call("GET","/me/home",null,"m0","MEMBER",200).path("reservations")).extracting(r -> r.path("id").asText()).doesNotContain(registration.path("id").asText());
        try(var tenant=TenantContext.open(CLUB)) {
            assertThat(lifecycle.finishEnded(clock.instant())).isEqualTo(2);
            assertThat(queries.historyRowsFor("m0",Instant.EPOCH,clock.instant())).filteredOn(r -> r.get("activityId").equals(id)).singleElement()
                    .satisfies(r -> assertThat(r).containsEntry("state","DONE").containsEntry("startsAtLocal","2026-09-19T09:00").doesNotContainKeys("endsAtLocal","endTime"));
        }
    }
    @Test void T_07_21_T_07_29_theD7ColumnsOfTheListAreTheActivityViewsOwnValues() throws Exception {
        // A free label per locale (OTHER) on an activity away from the club without hours or places; five rings; four rings.
        var fair=create(); String fairId=fair.path("id").asText();
        var patch=new LinkedHashMap<String,Object>(); patch.put("version",fair.path("version").asLong()); patch.put("type","OTHER"); patch.put("typeLabel",Map.of("ca","Fira","es","Feria"));
        patch.put("date","2026-09-20"); patch.put("location",Map.of("atClub",false,"name","Example park"));
        call("PATCH","/activities/"+fairId,patch,"admin","ADMIN",200);
        String all=ready(40,true).path("id").asText(); var four=ready(12,false); String fourId=four.path("id").asText();
        call("PATCH","/activities/"+fourId,Map.of("version",four.path("version").asLong(),"ringIds",List.of("s07-ring-0","s07-ring-1","s07-ring-2","s07-ring-3")),"admin","ADMIN",200);
        for(String language:List.of("ca","es","en")) assertListMatchesViews(language);
        var ca=read("/activities","admin","ADMIN","ca").path("items");
        assertThat(D7Columns.row(find(ca,"id",fairId))).isEqualTo("Activitat exemple · Fira · 2026-09-20 · null-null · allRings=false · rings=[] · location=Example park · 0+0/null · DRAFT");
        assertThat(D7Columns.row(find(ca,"id",all))).isEqualTo("Activitat exemple · seminari · 2026-09-15 · 18:00-20:00 · allRings=true · rings=[Ring 0, Ring 1, Ring 2, Ring 3, Ring 4] · location=null · 0+0/40 · DRAFT");
        assertThat(find(ca,"id",fourId).path("allRings").asBoolean()).isFalse();
        assertThat(find(read("/activities","admin","ADMIN","es").path("items"),"id",fairId).path("typeDisplay").asText()).isEqualTo("Feria");
        assertThat(find(read("/activities","admin","ADMIN","es").path("items"),"id",all).path("typeDisplay").asText()).isEqualTo("seminario");
        assertThat(find(read("/activities","admin","ADMIN","en").path("items"),"id",fairId).path("typeDisplay").asText()).as("the club's default locale").isEqualTo("Fira");
        // A ring leaves the catalog: the four rings are now all of them (R-07-11), in the list as in the view; the instructor reads the same row.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s07-ring-4")),new Update().set("active",false),"rings");
        assertListMatchesViews("ca");
        assertThat(find(read("/activities","instructor","INSTRUCTOR","ca").path("items"),"id",fourId).path("allRings").asBoolean()).isTrue();
        assertThat(find(read("/activities","instructor","INSTRUCTOR","ca").path("items"),"id",all).path("allRings").asBoolean()).isFalse();
        // `fields` selects the new columns like any other; an unknown one is still INVALID_FILTER.
        var selected=find(call("GET","/activities?fields=typeDisplay,allRings,location",null,"admin","ADMIN",200).path("items"),"id",fairId);
        assertThat(selected.path("typeDisplay").asText()).isEqualTo("Fira"); assertThat(selected.path("location").asText()).isEqualTo("Example park");
        assertThat(selected.path("allRings").isBoolean()).isTrue();
        // E5-T15 (review E4-T06 #3), E5-T20 step 3 (CONVENCIONS_API §4): a property that was not selected is left out, never null or a primitive default such as allRings: false.
        var titleOnly=find(call("GET","/activities?fields=title",null,"admin","ADMIN",200).path("items"),"id",fourId);
        assertThat(titleOnly.path("title").asText()).isEqualTo("Activitat exemple");
        assertThat(titleOnly.path("allRings").isBoolean()).as("not selected: "+titleOnly).isFalse();
        assertThat(titleOnly.has("allRings")).as("not selected: "+titleOnly).isFalse();
        call("GET","/activities?fields=ringIds",null,"admin","ADMIN",400);
    }
    void assertListMatchesViews(String language) throws Exception {
        var items=read("/activities?size=200","admin","ADMIN",language).path("items"); assertThat(items).hasSize(3);
        for(var item:items) {
            SnapshotSchemas.assertConforms(item,"ActivityListItem");
            D7Columns.assertSameAsView(item,read("/activities/"+item.path("id").asText(),"admin","ADMIN",language),language);
        }
    }
    @Test void T_07_21_aRegistrationListItemAlwaysSendsCancelReasonNullUntilItIsCancelled() throws Exception {
        // One choice for the OpenAPI and the serializer: always present, `null` until it applies (like `cancelledAt` and `position`).
        String id=published(1,false).path("id").asText(); var active=register(id,"m0",false,201); var waiting=register(id,"m1",true,201); var gone=register(id,"m2",true,201);
        call("POST","/activity-registrations/"+gone.path("id").asText()+"/cancellation",Map.of(),"m2","MEMBER",200);
        var items=call("GET","/activities/"+id+"/registrations",null,"admin","ADMIN",200).path("items");
        assertThat(items).hasSize(3); for(var item:items) SnapshotSchemas.assertConforms(item,"ActivityRegistrationListItem");
        var first=find(items,"registrationId",active.path("id").asText());
        assertNoEnd(first,"cancelReason"); assertNoEnd(first,"cancelledAt"); assertNoEnd(first,"position");
        var second=find(items,"registrationId",waiting.path("id").asText());
        assertNoEnd(second,"cancelReason"); assertThat(second.path("position").asInt()).isEqualTo(1);
        var cancelled=find(items,"registrationId",gone.path("id").asText());
        assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED"); assertThat(cancelled.path("cancelReason").asText()).isEqualTo("MEMBER");
        assertThat(cancelled.path("cancelledAt").asText()).isEqualTo(clock.instant().toString());
        // E5-T20 step 3 (CONVENCIONS_API §4): the schema requires only the row id, because `fields` leaves the other keys out.
        // So assertConforms above no longer proves that every key is sent without `fields`: the assertNoEnd calls above
        // (`has(field)` and `null`) prove it, and must stay (E5-T22, review E5-T20 #6).
        assertThat(SnapshotSchemas.required("ActivityRegistrationListItem")).containsExactly("registrationId");
        assertThat(SnapshotSchemas.schema("ActivityRegistrationListItem").at("/properties/cancelReason/enum")).anySatisfy(value -> assertThat(value.isNull()).isTrue());
        assertThat(SnapshotSchemas.violations(((ObjectNode)first.deepCopy()).put("cancelReason","UNKNOWN"),"ActivityRegistrationListItem")).isNotEmpty();
        // E5-T15 (review E4-T06 #1): a waitlisted registration keeps its position once cancelled; a promoted one has none.
        assertThat(cancelled.path("position").asInt()).as("kept after a waitlisted registration is cancelled").isEqualTo(2);
        call("POST","/activity-registrations/"+active.path("id").asText()+"/cancellation",Map.of(),"m0","MEMBER",200);
        items=call("GET","/activities/"+id+"/registrations",null,"admin","ADMIN",200).path("items");
        var promoted=find(items,"registrationId",waiting.path("id").asText());
        assertThat(promoted.path("state").asText()).isEqualTo("ACTIVE"); assertNoEnd(promoted,"position");
        var left=find(items,"registrationId",active.path("id").asText());
        assertThat(left.path("state").asText()).isEqualTo("CANCELLED"); assertNoEnd(left,"position");
        assertThat(find(items,"registrationId",gone.path("id").asText()).path("position").asInt()).isEqualTo(2);
        assertThat(SnapshotSchemas.schema("ActivityRegistrationListItem").at("/properties/position/description").asText()).contains("kept","promoted","never waitlisted");
    }
    /**
     * E5-T15 (E4-T06 question 2, R-07-13; review E4-T06 #2): the app rows (`ActivityRow`, `RegisteredActivity`) carry the
     * activity's own hours, `null` when absent, so a date-only activity never reads «0:00»; `endsAt` is described as derived.
     * Renamed in E5-T17 (review E5-T15 #5): it asserts R-07-13's row hours, not T-07-18 (`finishEnded`).
     */
    @Test void T_07_16_R_07_13_theAppRowsCarryStartAndEndTimesNullWhenAbsent() throws Exception {
        String lliga=startOnly().path("id").asText(), ended=published(5,false).path("id").asText(); // 19-09 from 09:00 · 15-09 18:00–20:00
        var fair=create(); String fairId=fair.path("id").asText(); var patch=new LinkedHashMap<String,Object>(); patch.put("version",fair.path("version").asLong());
        patch.put("date","2026-09-20"); patch.put("location",Map.of("atClub",false,"name","Example park"));
        patch.put("registrationFrom","2026-09-01"); patch.put("registrationTo","2026-09-18");
        call("PATCH","/activities/"+fairId,patch,"admin","ADMIN",200); call("POST","/activities/"+fairId+"/publication",Map.of(),"admin","ADMIN",200);
        var bookable=call("GET","/me/activities",null,"m0","MEMBER",200).path("bookable");
        assertThat(find(bookable,"id",fairId).path("startsAtLocal").asText()).isEqualTo("2026-09-20T00:00");
        assertNoEnd(find(bookable,"id",fairId),"startTime"); assertNoEnd(find(bookable,"id",fairId),"endTime");
        assertThat(find(bookable,"id",lliga).path("startTime").asText()).isEqualTo("09:00"); assertNoEnd(find(bookable,"id",lliga),"endTime");
        assertThat(find(bookable,"id",ended).path("startTime").asText()).isEqualTo("18:00"); assertThat(find(bookable,"id",ended).path("endTime").asText()).isEqualTo("20:00");
        for(var row:bookable) SnapshotSchemas.assertConforms(row,"ActivityRow");
        var dateOnly=register(fairId,"m0",false,201).path("activity"); var startOnly=register(lliga,"m0",false,201).path("activity");
        assertNoEnd(dateOnly,"startTime"); assertNoEnd(dateOnly,"endTime");
        assertThat(startOnly.path("startTime").asText()).isEqualTo("09:00"); assertNoEnd(startOnly,"endTime");
        SnapshotSchemas.assertConforms(dateOnly,"RegisteredActivity"); SnapshotSchemas.assertConforms(startOnly,"RegisteredActivity");
        var mine=call("GET","/me/activities",null,"m0","MEMBER",200).path("mine");
        assertNoEnd(find(mine,"activityId",fairId).path("activity"),"startTime");
        assertThat(find(mine,"activityId",lliga).at("/activity/startTime").asText()).isEqualTo("09:00");
        assertNoEnd(call("GET","/me/activities/"+fairId,null,"m0","MEMBER",200).path("myRegistration").path("activity"),"startTime");
        for(String schema:List.of("Activity","MemberActivityDetail"))
            assertThat(SnapshotSchemas.schema(schema).at("/properties/endsAt").toString()).as(schema).contains("next local 00:00","startTime/endTime");
    }
}
