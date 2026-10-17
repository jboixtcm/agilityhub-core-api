package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.PlatformConsoleQueries;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (session 10) of {@link ConsoleInvitationConsumer#handle} (S17 R-17-09, T-17-08: the invitation N-53): the
 * `ClubUpdated` that ConsoleAdministrators.invite publishes through PlatformClubChanges.publish carries the console user as actor,
 * and N-53's `inviter_name` is that user's account name. Collaborators are mocks; fictional data.
 */
class ConsoleInvitationConsumerSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final MagicLinkService links = mock(MagicLinkService.class);
    final PlatformConsoleQueries clubs = mock(PlatformConsoleQueries.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final ConsoleInvitationConsumer consumer = new ConsoleInvitationConsumer(links, clubs, accounts);

    @BeforeEach void setUp() { TenantContext.clear(); }
    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void T_17_08_theInvitationNamesTheConsoleUserWhoInvited() {
        when(accounts.findById("acc-platform")).thenReturn(Optional.of(new Account("acc-platform", "pat@example.test", "Pat Example", "ca",
                null, Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(),
                false, NOW.minusSeconds(86_400))));
        when(clubs.name("club-a")).thenReturn("Club Example");
        var event = new ClubConfigChanged("club-a", NOW, Map.of("clubId", "club-a", "diff", Map.of("adminInvitation", "acc-2")),
                "acc-platform", null, DomainEvent.Origin.BACKOFFICE);

        consumer.handle("event-1", event);

        verify(links).sendAdminInvitation("event-1", "acc-2", "Club Example", "Pat Example");
    }
}
