package com.agilityhub.core.payments.application.ports;

import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local/test stand-in of the SEPA writer (E8-T02, the `InMemoryPackBalances` pattern): the remittance of a run without its
 * pain.008 file — `fileKey` and `xsdValidatedAt` stay null — so the monthly cycle (T-12-09, T-12-13) and the definition of done
 * run on the Cànic seed, whose `SEPA_XML` has no creditor data yet. `messageId` is `{clubSlug}-{period}-{seq}` (R-12-12), the
 * sequence counting the period's earlier remittances (a rolled-back one included). **E8-T03** replaces it.
 */
@Component
@Profile({"local", "test"})
public class InMemoryRemittanceWriter implements RemittanceWriterPort {
    private final RemittanceRepository remittances; private final ClubConfigService configs; private final BillingProviderSettings providers;
    private final Clock clock;
    public InMemoryRemittanceWriter(RemittanceRepository remittances, ClubConfigService configs, BillingProviderSettings providers, Clock clock) {
        this.remittances = remittances; this.configs = configs; this.providers = providers; this.clock = clock;
    }
    @Override public Remittance write(BillingRun run, List<Collection> collections, LocalDate collectionDate) {
        String club = TenantContext.require();
        long sequence = remittances.forPeriod(run.period()).size() + 1L;
        String messageId = configs.get(club).club().slug() + "-" + run.period() + "-" + sequence;
        var creditor = providers.sepaCreditor().map(sepa -> new Remittance.Creditor(sepa.name(), sepa.id(), sepa.iban(), sepa.bic())).orElse(null);
        Money total = collections.stream().map(Collection::amount).reduce(Money::plus)
                .orElse(new Money(0, configs.get(club).club().currency()));
        var now = clock.instant();
        return new Remittance(UUID.randomUUID().toString(), club, run.id(), run.period(), messageId.length() > 35 ? messageId.substring(0, 35) : messageId,
                now, collectionDate.toString(), creditor, collections.stream().map(Collection::id).toList(), collections.size(), total,
                new Remittance.SequenceBreakdown(0, collections.size()), null, null, null, RemittanceStatus.GENERATED, null, null, null, now, run.createdByAccountId());
    }
}
