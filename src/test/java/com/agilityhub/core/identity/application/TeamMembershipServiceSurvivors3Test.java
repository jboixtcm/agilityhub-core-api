package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (session 10, NO_COVERAGE) of {@link TeamMembershipService}'s lookups (S05 team catalogs, S03 roles): an
 * account id without account and a member without membership are MEMBER_NOT_ACTIVE, and saving an unknown membership is NOT_FOUND
 * without writing anything; never a NullPointerException. Collaborators are mocks; fictional data.
 */
class TeamMembershipServiceSurvivors3Test {
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final AuditWriter audit = mock(AuditWriter.class);
    final TeamMembershipService team = new TeamMembershipService(memberships, accounts, mock(AccountSessionRepository.class),
            mock(AuditActorProvider.class), audit, mock(EventPublisher.class), Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), ZoneOffset.UTC));

    @Test void E11_T06_theAccountNameOfAnAccountIdWithoutAccountIsMemberNotActive() {
        when(accounts.findById("acc-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> team.accountName("acc-404"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.MEMBER_NOT_ACTIVE));
    }

    @Test void E11_T06_aMemberWithoutMembershipIsMemberNotActive() {
        when(memberships.findByMemberId("member-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> team.forMember("member-404"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.MEMBER_NOT_ACTIVE));
    }

    @Test void T_03_18_savingTheRolesOfAnUnknownMembershipIsNotFound() {
        when(memberships.findById("mem-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> team.save("mem-404", Set.of("MEMBER"), null, null, null))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(memberships, never()).saveTeam(any());
        verifyNoInteractions(audit);
    }
}
