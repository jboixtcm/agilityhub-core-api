package com.agilityhub.core.payments.application.ports;

import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Remittance;
import java.time.LocalDate;
import java.util.List;

/**
 * S12 R-12-11/12 (E8-T02 declares it): the run hands its `SEPA_XML` collections to the writer **inside** its transaction; the
 * writer returns the `Remittance` {@code remittanceId} to store, its pain.008 file generated, XSD-validated and stored before
 * the commit — a failure aborts the whole run and nothing is written. E8-T03's `SepaRemittanceWriter` is the implementation
 * in every profile (it replaced E8-T02's local/test stub and its `422 SEPA_NOT_CONFIGURED` null object).
 */
public interface RemittanceWriterPort {
    Remittance write(BillingRun run, String remittanceId, List<Collection> collections, LocalDate collectionDate);
}
