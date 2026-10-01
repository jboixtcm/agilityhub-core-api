package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * S12 (E8-T02): the open club's `CLUB.paymentProviders` as billing reads them — which providers are enabled (R-12-28:
 * `PROVIDER_DISABLED`) and the SEPA creditor a remittance snapshots (R-12-12: `SEPA_XML {creditorName, creditorId, iban,
 * bic?, suffix}`). The creditor's IBAN only goes into the remittance document (masked in every response) and the file.
 */
@Service
public class BillingProviderSettings {
    /** `configured` = name, identifier and IBAN present (S17 R-17-05); otherwise a remittance is `SEPA_NOT_CONFIGURED`. */
    public record SepaCreditor(String name, String id, @Sensitive String iban, String bic, String suffix) {
        public boolean configured() { return present(name) && present(id) && present(iban); }
        @Override public String toString() { return "SepaCreditor[name=" + name + ", id=" + id + ", configured=" + configured() + "]"; }
    }
    private final ClubRepository clubs;
    public BillingProviderSettings(ClubRepository clubs) { this.clubs = clubs; }

    /** The enabled providers (`SEPA_XML`, `STRIPE`, `MANUAL`), `enabled: true` only (E3-T14). */
    public Set<String> enabledProviders() {
        return providers().entrySet().stream().filter(entry -> PaymentProviderFlags.enabled(entry.getValue())).map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
    /** `SEPA_XML` enabled, with or without its creditor data; empty when the club does not collect by direct debit. */
    public Optional<SepaCreditor> sepaCreditor() {
        if (!(providers().get("SEPA_XML") instanceof Map<?, ?> sepa) || !PaymentProviderFlags.enabled(sepa)) { return Optional.empty(); }
        return Optional.of(new SepaCreditor(text(sepa.get("creditorName")), text(sepa.get("creditorId")), text(sepa.get("iban")), text(sepa.get("bic")),
                text(sepa.get("suffix"))));
    }
    private Map<String, Object> providers() {
        var club = clubs.findById(TenantContext.require()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return club.paymentProviders() == null ? Map.of() : club.paymentProviders();
    }
    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
