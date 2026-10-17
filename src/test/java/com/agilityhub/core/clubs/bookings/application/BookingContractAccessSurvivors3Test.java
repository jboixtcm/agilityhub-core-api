package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.scheduling.application.SchedulingContractAccess;
import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingContractAccess} (S08 §6, T-08-26): the class guard of the staff class
 * reads is S06's (`SchedulingContractAccess#classSession`), so another club's or an unknown class is 404 NOT_FOUND.
 */
class BookingContractAccessSurvivors3Test {
    final SchedulingContractAccess scheduling = mock(SchedulingContractAccess.class);
    final BookingContractAccess access = new BookingContractAccess(scheduling, mock(ModuleGuard.class));

    @Test void T_08_26_anotherClubsClassIsNotFound() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(scheduling).classSession("class-b");

        assertThatThrownBy(() -> access.classSession("class-b"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        access.classSession("class-a");
        verify(scheduling).classSession("class-a");
    }
}
