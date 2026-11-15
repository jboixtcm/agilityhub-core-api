package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.HandoffCodeRepository;
import com.agilityhub.core.platform.application.ClubAppUrls;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FE-03 (pre-production audit), S01 R-01-13: a handoff switches between the club app and the back office. A club-app session that
 * asks for a code into the club app itself (no client does) is refused with 403 NO_MEMBERSHIP before anything is read or stored;
 * the back office's codes, into either app, still go on to the usual checks.
 */
class HandoffServiceAppSwitchTest {
    final HandoffCodeRepository codes = mock(HandoffCodeRepository.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final HandoffService service = new HandoffService(codes, mock(IdentityService.class), mock(TokenService.class), transactions,
            mock(ClubAppUrls.class), mock(SecurityEvents.class), Clock.systemUTC(), mock(ImpersonationService.class));

    @Test void FE_03_theClubAppGetsNoCodeIntoItself() {
        assertThatThrownBy(() -> service.create("account-a", "clubs-app", "family-a", "clubs-app"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.NO_MEMBERSHIP));
        verifyNoInteractions(transactions, codes);
    }

    @Test void FE_03_theOtherPairsGoOnToTheUsualChecks() {
        when(transactions.run(any())).thenReturn(null);
        service.create("account-a", "clubs-app", "family-a", "clubs-admin");
        service.create("account-a", "clubs-admin", "family-a", "clubs-app");
        service.create("account-a", "clubs-admin", "family-a", "clubs-admin");
        verify(transactions, times(3)).run(any(Supplier.class));
    }
}
