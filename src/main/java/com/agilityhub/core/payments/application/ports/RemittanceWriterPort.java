package com.agilityhub.core.payments.application.ports;

import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Remittance;
import java.time.LocalDate;
import java.util.List;

/**
 * S12 R-12-11/12 (E8-T02 declares it): the run hands its `SEPA_XML` collections to the writer **inside** its transaction; the
 * writer returns the `Remittance` to store, its pain.008 file generated and XSD-validated before the commit — a failure
 * aborts the whole run and nothing is written. The production default ({@link BillingPortDefaults}) has no writer yet and
 * answers `422 SEPA_NOT_CONFIGURED`; under the `local` and `test` profiles {@link InMemoryRemittanceWriter} returns a stub
 * without a file. **E8-T03** (`SepaRemittanceWriter`) replaces both.
 */
public interface RemittanceWriterPort {
    Remittance write(BillingRun run, List<Collection> collections, LocalDate collectionDate);
}
