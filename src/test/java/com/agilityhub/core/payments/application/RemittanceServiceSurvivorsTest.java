package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.ExportFileStore;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link RemittanceService} (S12 §6, R-12-14; T-12-21): an unknown remittance is `404 NOT_FOUND`,
 * and the local store's signed route answers `404` for an unknown remittance or one without a stored file, without opening
 * anything.
 */
class RemittanceServiceSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    static final long EXPIRES = NOW.getEpochSecond() + 300;

    final RemittanceRepository remittances = mock(RemittanceRepository.class);
    final ExportFileStore files = mock(ExportFileStore.class);
    final RemittanceService service = new RemittanceService(remittances, files, mock(ClubConfigService.class), mock(AuditWriter.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void T_12_21_anUnknownRemittanceIsNotFound() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertCode(() -> service.get("remittance-unknown"), ErrorCode.NOT_FOUND);
        }
    }

    @Test void T_12_21_theSignedRouteAnswersNotFoundForAnUnknownRemittanceOrOneWithoutAFile() {
        when(remittances.findById("remittance-1")).thenReturn(Optional.of(remittance("remittance-1", null)));

        assertCode(() -> service.download(CLUB, "remittance-1", EXPIRES, "signature-fake"), ErrorCode.NOT_FOUND);
        assertCode(() -> service.download(CLUB, "remittance-unknown", EXPIRES, "signature-fake"), ErrorCode.NOT_FOUND);

        verify(files).verifyLocal(CLUB, RemittanceService.localPath(CLUB, "remittance-1"), EXPIRES, "signature-fake");
        verify(files, never()).open(any());
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    static Remittance remittance(String id, String fileKey) {
        return new Remittance(id, CLUB, "run-1", "2026-10", "example-club-2026-10-1", NOW, "2026-10-01", null, List.of(), 0,
                new Money(0, "EUR"), null, fileKey, null, null, RemittanceStatus.ROLLED_BACK, null, null, 1L, NOW, null);
    }
}
