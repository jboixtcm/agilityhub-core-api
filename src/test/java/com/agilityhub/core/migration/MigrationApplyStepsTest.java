package com.agilityhub.core.migration;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.identity.application.MigrationIdentityService;
import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.EventPublisher;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationApplyStepsTest {
    final CensusMigrationService census=mock(CensusMigrationService.class);
    final MigrationIdentityService identities=mock(MigrationIdentityService.class);
    final MigrationClubAccess clubs=mock(MigrationClubAccess.class);
    final MigrationBankVault vault=mock(MigrationBankVault.class);
    final MigrationRunRepository runs=mock(MigrationRunRepository.class);
    final EventPublisher events=mock(EventPublisher.class);
    final Instant now=Instant.parse("2026-10-23T10:00:00Z");
    final MigrationApplyService apply=new MigrationApplyService(census,identities,clubs,vault,runs,events,Clock.fixed(now,ZoneOffset.UTC));
    final MigrationRun run=new MigrationRun("run-1","club-1","PLAYOFF","APPLY","STAGING",2,"RUNNING",now,null,Map.of());
    final PlayoffInput.Row source=new PlayoffInput.Row("members",2,Map.of("id","source-1"));

    @Test void T_18_13_partialPersonOrStateWritesCannotEraseThePaymentMethod() {
        var person=new PlayoffPlanner.Change("members","member-1",Map.of("firstName","Fictional"),source);
        var state=new PlayoffPlanner.Change("members","member-1",Map.of("status","ACTIVE"),source);
        apply.applyChanges(run,List.of(person,state));
        verify(census).member("member-1",Map.of("firstName","Fictional"));
        verify(census).member("member-1",Map.of("status","ACTIVE"));
        verifyNoInteractions(vault,identities,clubs,runs,events);
    }
    @Test void T_18_13_paymentEncryptsOnlyItsSelectedPatchWithoutMutatingThePlan() {
        var payment=Map.<String,Object>of("type","SEPA_DD","iban","FICTIONAL1234");
        var change=new PlayoffPlanner.Change("members","member-1",Map.of("paymentMethod",payment),source);
        when(vault.encrypt("FICTIONAL1234","club-1","member-1")).thenReturn("fictional-encrypted-value");
        apply.applyChanges(run,List.of(change));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> fields=ArgumentCaptor.forClass(Map.class);
        verify(census).member(eq("member-1"),fields.capture());
        assertThat(fields.getValue()).containsOnlyKeys("paymentMethod");
        var written=(Map<?,?>)fields.getValue().get("paymentMethod");
        assertThat(written.get("ibanEncrypted")).isEqualTo("fictional-encrypted-value");
        assertThat(written.containsKey("iban")).isFalse();
        assertThat(change.fields()).containsEntry("paymentMethod",payment);
        verifyNoInteractions(identities,clubs);
    }
    @Test void T_18_13_identityAndNumberPortsHaveNoUnselectedCensusWrites() {
        var identity=new PlayoffPlanner.Identity("member-1","fictional@example.test","Fictional","ca",true,Set.of("MEMBER"));
        when(identities.apply("member-1","fictional@example.test","Fictional","ca",true,Set.of("MEMBER"))).thenReturn("account-1");
        apply.applyIdentities(List.of(identity));
        verify(census).member("member-1",Map.of("accountId","account-1")); verifyNoMoreInteractions(census); verifyNoInteractions(clubs,vault);
        apply.reserveNumbers(25); verify(clubs).reserveNumbers(25);
    }
    @Test void T_18_13_legacyApplyRetainsAllChangesIdentitiesAndNumberReservation() {
        var member=new PlayoffPlanner.Change("members","member-1",Map.of("firstName","Fictional","paymentMethod",Map.of("type","MANUAL")),source);
        var dog=new PlayoffPlanner.Change("dogs","dog-1",Map.of("memberId","member-1"),source);
        var group=new PlayoffPlanner.Change("family_groups","group-1",Map.of("holderMemberId","member-1"),source);
        var leave=new PlayoffPlanner.Change("leaves","leave-1",Map.of("memberId","member-1","effectiveDate","2026-12-01"),source);
        var plan=new PlayoffPlanner.Plan(List.of(member,dog,group,leave),List.of(),25,List.of());
        apply.apply(run,plan);
        verify(census).member("member-1",member.fields()); verify(census).dog("dog-1",dog.fields()); verify(census).group("group-1",group.fields());
        verify(census).plannedLeave("leave-1","member-1","2026-12-01"); verify(clubs).reserveNumbers(25); verifyNoInteractions(vault,identities);
    }
}
