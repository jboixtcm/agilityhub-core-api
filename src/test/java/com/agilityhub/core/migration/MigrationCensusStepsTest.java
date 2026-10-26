package com.agilityhub.core.migration;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationCensusStepsTest {
    final CensusMigrationService census=mock(CensusMigrationService.class);
    final MigrationCensusSteps selector=new MigrationCensusSteps(census);
    final PlayoffInput.Row source=new PlayoffInput.Row("members",2,Map.of("id","source-1"));
    final PlayoffPlanner.Change member=new PlayoffPlanner.Change("members","member-1",new LinkedHashMap<>(Map.of(
            "firstName","Imported","sourceIds",Map.of("playoffMemberId","source-1"),"externalIds",Map.of("playoff",List.of("source-1")),
            "status","ACTIVE","paymentMethod",Map.of("type","MANUAL"),"planId","plan-new","nextInvoiceDate","2026-11-01",
            "bookingBlock",Map.of("active",true),"familyGroupId","group-1","memberNumber",7)),source);
    final PlayoffPlanner.Change dog=new PlayoffPlanner.Change("dogs","dog-1",Map.of(
            "memberId","member-1","name","Imported dog","status","ACTIVE","sourceIds",Map.of("playoffMemberId","source-1")),source);
    final PlayoffPlanner.Identity identity=new PlayoffPlanner.Identity("member-1","fictional@example.test","Fictional","ca",true,Set.of("MEMBER"));
    @BeforeEach void empty() {
        when(census.snapshot(anyString())).thenReturn(List.of());
        member.fields().put("contactEmails",List.of(Map.of("email","fictional@example.test")));
    }
    PlayoffPlanner.Plan plan() {
        return new PlayoffPlanner.Plan(List.of(member,dog),List.of(identity),7,List.of(
                new MigrationReport.Entry("members",2,"members","CREATED",""),
                new MigrationReport.Entry("members",2,"dogs","CREATED",""),
                new MigrationReport.Entry("members",2,"accounts","CREATED",""),
                new MigrationReport.Entry("members",2,"members","WARNING","NO_BANK_ACCOUNT")));
    }
    Map<String,Object> storedMember(String state) {
        return Map.of("_id","member-1","sourceIds",Map.of("playoffMemberId","source-1"),"firstName","Stored",
                "status",state,"accountId","account-1","paymentMethod",Map.of("type","CARD"),"planId","plan-old","bookingBlock",Map.of("active",true),
                "contactEmails",List.of(Map.of("email","fictional@example.test")));
    }
    void storedDog() { when(census.snapshot("dogs")).thenReturn(List.of(Map.of("_id","dog-1","sourceIds",Map.of("playoffMemberId","source-1"),
            "memberId","member-1","name","Stored dog","status","INACTIVE"))); }
    MigrationCensusSteps.Selection select(String steps) { return selector.select(plan(),MigrationSteps.parse(steps)); }
    void dependency(String steps,int step,int required) {
        assertThatThrownBy(()->select(steps)).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.code()).isEqualTo(ErrorCode.INPUT_SCHEMA_MISMATCH);
            assertThat(error.details()).containsEntry("step",step).containsEntry("requiredStep",required);
        });
    }

    @Test void T_18_13_personOnlyCreatesPendingPersonWithoutPaymentStateDogOrAccountWrites() {
        var selected=select("1-3"); var person=selected.changes(3).getFirst().fields();
        assertThat(person).containsEntry("firstName","Imported").containsEntry("status","PENDING")
                .doesNotContainKeys("paymentMethod","planId","nextInvoiceDate","bookingBlock");
        assertThat(selected.changes(4)).isEmpty(); assertThat(selected.identities()).isEmpty(); assertThat(selected.maximumNumber()).isEqualTo(7);
        assertThat(selected.rows()).allMatch(row->row.entity().equals("members"));
        assertThat(selected.preview().changes()).hasSize(1);
    }
    @Test void T_18_13_paymentStepPreservesStoredPersonStateAndAccessInEffectivePreview() {
        when(census.snapshot("members")).thenReturn(List.of(storedMember("LEFT")));
        var selected=select("5");
        assertThat(selected.changes(5).getFirst().fields()).containsOnlyKeys("paymentMethod","planId","nextInvoiceDate");
        assertThat(selected.preview().changes().getFirst().fields()).containsEntry("firstName","Stored").containsEntry("status","LEFT")
                .containsEntry("accountId","account-1").containsEntry("planId","plan-new");
        assertThat(selected.maximumNumber()).isZero(); assertThat(selected.identities()).isEmpty();
    }
    @Test void T_18_13_missingImportedMemberIsNeverCreatedByADependentStep() {
        for (int step:List.of(4,5,6,9)) { dependency(Integer.toString(step),step,3); }
        when(census.snapshot("members")).thenReturn(List.of(Map.of("_id","member-1","status","ACTIVE")));
        dependency("5",5,3);
    }
    @Test void T_18_13_identityRequiresEffectiveActiveStateAndNeverInventsIt() {
        dependency("3,9",9,6);
        when(census.snapshot("members")).thenReturn(List.of(storedMember("PENDING")));
        dependency("9",9,6);
        when(census.snapshot("members")).thenReturn(List.of(storedMember("ACTIVE")));
        assertThat(select("9").identities()).singleElement().extracting(PlayoffPlanner.Identity::name).isEqualTo("Stored");
        var full=select("3-6,9");
        assertThat(full.identities()).singleElement().extracting(PlayoffPlanner.Identity::name).isEqualTo("Imported");
        assertThat(full.preview().changes().stream().filter(change->change.entity().equals("members")).findFirst().orElseThrow().fields())
                .containsEntry("status","ACTIVE");
    }
    @Test void T_18_13_dogDetailsAndDogStateAreIndependentAndMissingDogCannotBePatched() {
        when(census.snapshot("members")).thenReturn(List.of(storedMember("ACTIVE")));
        dependency("6",6,4);
        assertThat(select("4").changes(4).getFirst().fields()).containsEntry("status","INACTIVE");
        storedDog();
        var details=select("4");
        assertThat(details.changes(4).getFirst().fields()).doesNotContainKey("status");
        assertThat(details.preview().changes().getFirst().fields()).containsEntry("status","INACTIVE").containsEntry("name","Imported dog");
        var state=select("6");
        assertThat(state.changes(6).stream().filter(change->change.entity().equals("dogs")).findFirst().orElseThrow().fields())
                .containsExactlyEntriesOf(Map.of("status","ACTIVE"));
        assertThat(state.preview().changes().stream().filter(change->change.entity().equals("dogs")).findFirst().orElseThrow().fields())
                .containsEntry("name","Stored dog").containsEntry("status","ACTIVE");
    }
    @Test void T_18_13_groupsMayReferForwardOnlyToSelectedPeopleAndBacklinksWaitForTheirInsert() {
        var groupSource=new PlayoffInput.Row("groups",2,Map.of());
        var group=new PlayoffPlanner.Change("family_groups","group-1",Map.of("memberIds",List.of("member-1"),"holderMemberId","member-1"),groupSource);
        var plan=new PlayoffPlanner.Plan(List.of(member,group),List.of(),7,List.of(new MigrationReport.Entry("groups",2,"familyGroups","CREATED","")));
        assertThatThrownBy(()->selector.select(plan,MigrationSteps.parse("2"))).isInstanceOf(ApiException.class);
        var fresh=selector.select(plan,MigrationSteps.parse("2-3"));
        assertThat(fresh.changes(2)).extracting(PlayoffPlanner.Change::entity).containsExactly("family_groups");
        assertThat(fresh.changes(3).getFirst().fields()).containsEntry("familyGroupId","group-1");
        when(census.snapshot("members")).thenReturn(List.of(storedMember("ACTIVE")));
        var existing=selector.select(plan,MigrationSteps.parse("2"));
        assertThat(existing.changes(2)).extracting(PlayoffPlanner.Change::entity).containsExactly("family_groups","members");
        assertThat(existing.changes(2).getLast().fields()).containsExactlyEntriesOf(Map.of("familyGroupId","group-1"));
        assertThat(selector.select(plan,MigrationSteps.parse("3")).changes(3).getFirst().fields()).doesNotContainKey("familyGroupId");
        when(census.snapshot("members")).thenReturn(List.of());
        when(census.snapshot("family_groups")).thenReturn(List.of(Map.of("_id","group-1","sourceIds",Map.of("playoffGroupId","g1"),"memberIds",List.of("member-1"))));
        assertThat(selector.select(plan,MigrationSteps.parse("3")).changes(3).getFirst().fields()).containsEntry("familyGroupId","group-1");
    }
    @Test void T_18_13_unsetsAndFutureLeavesBelongOnlyToTheStateStep() {
        when(census.snapshot("members")).thenReturn(List.of(storedMember("ACTIVE"))); storedDog();
        member.fields().put("leftAt",null);
        var leave=new PlayoffPlanner.Change("leaves","leave-1",Map.of("memberId","member-1","effectiveDate","2026-12-01"),source);
        var plan=new PlayoffPlanner.Plan(List.of(member,dog,leave),List.of(),7,List.of(new MigrationReport.Entry("members",2,"leaves","CREATED","")));
        var state=selector.select(plan,MigrationSteps.parse("6"));
        assertThat(state.changes(6).getFirst().fields()).containsKey("leftAt").containsEntry("leftAt",null);
        assertThat(state.changes(6)).extracting(PlayoffPlanner.Change::entity).contains("leaves");
        assertThat(selector.select(plan,MigrationSteps.parse("3")).changes(3).getFirst().fields()).doesNotContainKey("leftAt");
    }
    @Test void T_18_13_catalogCheckOnlyReportsGlobalValidationWithoutClaimingExcludedWrites() {
        var original=plan(); var rows=new ArrayList<>(original.rows());
        rows.add(new MigrationReport.Entry("members",2,"members","ERROR","REEXECUTION_UNSUPPORTED"));
        rows.add(new MigrationReport.Entry("members",2,"familyGroups","PROPOSED","EMAIL_SHARED"));
        var plan=new PlayoffPlanner.Plan(original.changes(),original.identities(),7,rows);
        var selected=selector.select(plan,MigrationSteps.parse("1"));
        assertThat(selected.changes()).isEmpty(); assertThat(selected.preview().changes()).isEmpty(); assertThat(selected.identities()).isEmpty();
        assertThat(selected.rows()).extracting(MigrationReport.Entry::outcome).containsExactly("WARNING","ERROR");
    }
    @Test void T_18_13_selectedSkippedSourcesRemainVisibleWithoutInventingWrites() {
        var rows=List.of(new MigrationReport.Entry("members",9,"members","SKIPPED",""),
                new MigrationReport.Entry("members",9,"dogs","SKIPPED",""),
                new MigrationReport.Entry("members",9,"leaves","SKIPPED",""),
                new MigrationReport.Entry("groups",9,"familyGroups","SKIPPED",""));
        var plan=new PlayoffPlanner.Plan(List.of(),List.of(),23,rows);
        assertThat(selector.select(plan,MigrationSteps.parse("3")).rows()).extracting(MigrationReport.Entry::entity).containsExactly("members");
        assertThat(selector.select(plan,MigrationSteps.parse("2,4,6")).rows()).extracting(MigrationReport.Entry::entity).containsExactly("dogs","leaves","familyGroups");
    }
    @Test void T_18_13_identityCannotSkipChangedBookingBlockOrScheduledLeave() {
        var stored=new LinkedHashMap<>(storedMember("ACTIVE")); stored.put("bookingBlock",Map.of("active",false));
        when(census.snapshot("members")).thenReturn(List.of(stored));
        dependency("9",9,6);
        stored.put("bookingBlock",Map.of("active",true));
        member.fields().put("leaveDate","2026-12-01");
        dependency("9",9,6);
        stored.put("leaveDate","2026-12-01");
        assertThat(select("9").identities()).hasSize(1);
        stored.remove("leaveDate"); storedDog();
        assertThat(select("6,9").identities()).hasSize(1);
    }
    @Test void T_18_13_identityRequiresEffectivePrimaryEmailAndUsesPreservedPersonName() {
        var stored=new LinkedHashMap<>(storedMember("ACTIVE"));
        stored.put("contactEmails",List.of(Map.of("email","previous@example.test"),Map.of("email",identity.email())));
        stored.put("lastName1","Preserved"); stored.put("lastName2","Family");
        when(census.snapshot("members")).thenReturn(List.of(stored));
        dependency("9",9,3);
        stored.put("contactEmails",List.of(Map.of("email","FICTIONAL@example.test")));
        assertThat(select("9").identities()).containsExactly(new PlayoffPlanner.Identity("member-1",identity.email(),
                "Stored Preserved Family","ca",true,Set.of("MEMBER")));
        stored.put("contactEmails",List.of()); dependency("9",9,3);
        assertThat(select("3,9").identities()).singleElement().extracting(PlayoffPlanner.Identity::name).isEqualTo("Imported Preserved Family");
    }
}
