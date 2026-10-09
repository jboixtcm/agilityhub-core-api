package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link MembershipRepository}: the club's active-admin count guards the last ADMIN
 * (RoleAssignmentService:90,191) and a member's team membership is found by its member id (TeamMembershipService:39,44),
 * both within the current club.
 */
class MembershipRepositorySurvivorsTest {
    static final String CLUB = "club-a";

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final MembershipRepository repository = new MembershipRepository(mongo);

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open(CLUB); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void E11_T06_activeAdminsCountsTheClubsActiveAdmins() {
        when(mongo.count(any(Query.class), eq(Membership.class))).thenReturn(2L);

        assertThat(repository.activeAdmins()).isEqualTo(2L);

        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(query.capture(), eq(Membership.class));
        assertThat(query.getValue().getQueryObject())
                .isEqualTo(new Document("clubId", CLUB).append("roles", "ADMIN").append("status", "ACTIVE"));
    }

    @Test void E11_T06_findByMemberIdReturnsTheClubsMembership() {
        // As SignupIdentityService.java:39 writes it, with its `createdAt`.
        var membership = new Membership("ms-1", "acc-1", CLUB, "member-1", Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER, false, null,
                java.time.Instant.parse("2026-09-01T10:00:00Z"), null);
        when(mongo.findOne(any(Query.class), eq(Membership.class))).thenReturn(membership);

        assertThat(repository.findByMemberId("member-1")).contains(membership);

        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findOne(query.capture(), eq(Membership.class));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("clubId", CLUB).append("memberId", "member-1"));
    }
}
