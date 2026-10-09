package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.domain.PackBalanceState;
import com.agilityhub.core.payments.persistence.PackBalance;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivor of {@link PackViews} (S12 R-12-23): the view names the pack's plan in the request's language. */
class PackViewsSurvivorsTest {
    final BillingCatalogAccess catalog = mock(BillingCatalogAccess.class);
    final PackViews views = new PackViews(catalog);

    @AfterEach void tearDown() { LocaleContextHolder.resetLocaleContext(); }

    @Test void T_12_07_aPackViewCarriesItsPlanNameInTheRequestLocale() {
        var name = new LocalizedText(Map.of("ca", "Pack 10 classes", "es", "Bono 10 clases"), "ca");
        when(catalog.plan("plan-pack")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPlan("plan-pack", "P10", "PACK", null, 1, name, null)));
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        var pack = new PackBalance("pack-1", "club-a", "member-1", "dog-1", "plan-pack", null, 10, 0, 10, "2026-06-12", "2026-11-11",
                PackBalanceState.ACTIVE, List.of(), null, null, null, Map.of(), 0L, Instant.parse("2026-06-12T10:00:00Z"), null);

        assertThat(views.view(pack)).containsEntry("planName", "Bono 10 clases");
    }
}
