package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor of {@link PackAuditLoader} (S12 R-12-23): the loader serves the entity type `PackBalanceService.adjust`
 * audits (`entityType = "'PackBalance'"`), so the audit reads the balance's before-state through it.
 */
class PackAuditLoaderSurvivorsTest {
    @Test void T_12_07_theLoaderServesTheAuditedPackBalanceType() {
        assertThat(new PackAuditLoader(mock(PackBalanceRepository.class)).entityType()).isEqualTo("PackBalance");
    }
}
