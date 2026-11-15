package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusListProjection;
import com.agilityhub.core.clubs.census.persistence.CensusReferences;
import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Dog;
import com.agilityhub.core.clubs.census.persistence.FamilyGroup;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AUTHZ-02 (S03 R-03-31, MATRIU_PERMISOS «Abonats»): an INSTRUCTOR reads ACTIVE and PENDING members only and never the postal
 * address; the ADMIN's views are unchanged. No Spring context or Mongo.
 */
class CensusInstructorViewTest {
    final ClubConfigService configs = mock(ClubConfigService.class);
    final ClubConfig config = mock(ClubConfig.class);
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        TenantContext.clear();
        tenant = TenantContext.open("club-a");
        when(config.modules()).thenReturn(Set.of(Module.values()));
        when(config.get("levels.enabled", Boolean.class)).thenReturn(true);
        when(configs.get("club-a")).thenReturn(config);
    }
    @AfterEach void tearDown() { tenant.close(); SecurityContextHolder.clearContext(); }

    static void as(String... roles) {
        var jwt = Jwt.withTokenValue("fictional").header("alg", "none").subject("account-a").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(roles).<org.springframework.security.core.GrantedAuthority>map(role -> () -> "ROLE_" + role).toList()));
    }
    ListDefinition members(String role) {
        var projection = mock(CensusListProjection.class);
        as(role);
        new CensusLists(projection, configs).dataset("members");
        var captor = ArgumentCaptor.forClass(ListDefinition.class);
        verify(projection).dataset(captor.capture(), eq(true), eq("ADMIN".equals(role)));
        return captor.getValue();
    }

    @Test void AUTHZ_02_theInstructorsMemberListHasNoPostalAddressColumnFilterSortOrField() {
        var definition = members("INSTRUCTOR");
        assertThat(definition.columns()).doesNotContain("city", "postalCode");
        assertThat(definition.fields()).doesNotContain("address", "city", "postalCode").contains("contactEmails", "phones", "status");
        assertThat(definition.filters()).doesNotContainKeys("city", "postalCode");
        assertThat(definition.sorts()).doesNotContainKey("city");
    }
    @Test void AUTHZ_02_theAdminsMemberListKeepsTheCityAndPostalCode() {
        var definition = members("ADMIN");
        assertThat(definition.columns()).contains("city", "postalCode");
        assertThat(definition.fields()).contains("city", "postalCode");
        assertThat(definition.filters()).containsKeys("city", "postalCode");
        assertThat(definition.sorts()).containsKey("city");
    }

    CensusListProjection projection() {
        when(config.modules()).thenReturn(Set.of());
        var club = mock(ClubConfig.ClubView.class);
        when(club.defaultLocale()).thenReturn("ca");
        when(config.club()).thenReturn(club);
        return new CensusListProjection(mock(MongoTemplate.class), configs, mock(ClubClock.class), mock(AttachmentService.class));
    }
    ListDefinition definition(String... fields) {
        return new ListDefinition("members", Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of("lastName,asc"), Set.of(fields));
    }
    @Test void AUTHZ_02_theInstructorsMemberRowsAreTheActiveAndPendingOnesBeforeAnyJoin() {
        var dataset = projection().dataset(definition("id", "address", "city", "postalCode", "contactEmails"), true, false);
        assertThat(dataset.stages().getFirst()).isEqualTo(new Document("$match", new Document("status", new Document("$in", List.of("ACTIVE", "PENDING")))));
        assertThat(dataset.stages().stream().skip(1)).noneMatch(stage -> stage.containsKey("$match"));
        assertThat(dataset.projection()).containsKey("contactEmails").doesNotContainKeys("address", "city", "postalCode");
    }
    @Test void AUTHZ_02_theAdminsMemberRowsAreEveryStatusWithTheirCity() {
        var dataset = projection().dataset(definition("id", "city", "postalCode"), true, true);
        assertThat(dataset.stages()).noneMatch(stage -> stage.containsKey("$match"));
        assertThat(dataset.projection()).containsKeys("city", "postalCode");
    }

    @SuppressWarnings("unchecked")
    CensusQuery query(String status) {
        CensusRepository<Member> members = mock(CensusRepository.class);
        CensusRepository<Dog> dogs = mock(CensusRepository.class);
        var member = new Member(); member.id = "member-a"; member.clubId = "club-a"; member.status = status;
        member.firstName = "Fictional"; member.lastName1 = "Person";
        member.contactEmails = List.of(Map.of("email", "person@example.test", "bounced", false));
        member.phones = List.of(Map.of("prefix", "+34", "number", "600000001"));
        member.address = Map.of("street", "1 Fictional Street", "postalCode", "08349", "city", "Fictional Town", "country", "ES");
        when(members.require("member-a")).thenReturn(member);
        when(dogs.matching(any())).thenReturn(List.of());
        when(config.modules()).thenReturn(Set.of());
        var clock = mock(ClubClock.class);
        when(clock.today("club-a")).thenReturn(LocalDate.of(2026, 11, 13));
        var access = new CensusAccess(members, dogs, mock(CensusRepository.class), mock(CensusReferences.class), configs);
        return new CensusQuery(access, mock(DocumentService.class), mock(AttachmentService.class), clock);
    }
    @ParameterizedTest @ValueSource(strings = {"LEFT", "INACTIVE"})
    void AUTHZ_02_anExMembersRecordIsNotFoundForAnInstructor(String status) {
        assertThatThrownBy(() -> query(status).member("member-a", false))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "PENDING"})
    void AUTHZ_02_theInstructorsRecordKeepsTheContactAndNeverTheAddress(String status) {
        var view = query(status).member("member-a", false);
        assertThat(view).containsKeys("contactEmails", "phones", "status").doesNotContainKey("address");
    }
    @Test void AUTHZ_02_theInstructorStatusesAreActiveAndPending() {
        assertThat(com.agilityhub.core.clubs.census.domain.CensusRules.INSTRUCTOR_VISIBLE_STATUSES).containsExactly("ACTIVE", "PENDING");
        assertThat(com.agilityhub.core.clubs.census.domain.CensusRules.visibleToInstructor("LEFT")).isFalse();
        assertThat(com.agilityhub.core.clubs.census.domain.CensusRules.visibleToInstructor(null)).isFalse();
    }
}
