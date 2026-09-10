package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.clubs.catalogs.application.SignupPlanData;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;
import com.agilityhub.core.clubs.catalogs.persistence.Plan;
import com.agilityhub.core.clubs.catalogs.persistence.Price;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SignupCatalogAdapterTest {
    private final LocalDate date = LocalDate.of(2026, 8, 17);
    private final Instant at = Instant.parse("2026-08-17T12:00:00Z");
    private final LocalizedText text = new LocalizedText(Map.of("ca", "Example"), "ca");
    private Plan plan(PlanType type, EntryFee entry) {
        return new Plan("plan", "club", "EXAMPLE", text, type, type == PlanType.MONTHLY ? BillingMode.MONTHLY_FEE : null,
                1, entry, type == PlanType.PACK ? new Pack(6, 3) : null, null, text, new Texts(text, text, text),
                true, true, 5, true, 0, at, at, "actor", "actor");
    }
    private Price price(String id, String club, String plan, PriceConcept concept, LocalDate from, LocalDate to) {
        return new Price(id, club, plan, concept, new Money(6000, "EUR"), BigDecimal.ZERO, from, to, 0, at, at, "actor", "actor");
    }
    @Test void T_04_09_catalogProjectionUsesOnlyCurrentPricesOfTheSamePlanAndTenant() {
        var data = SignupPlanData.from(plan(PlanType.MONTHLY, null), List.of(
                price("current", "club", "plan", PriceConcept.MONTHLY_FEE, date.minusDays(1), date),
                price("expired", "club", "plan", PriceConcept.MONTHLY_FEE, date.minusDays(2), date.minusDays(1)),
                price("future", "club", "plan", PriceConcept.MONTHLY_FEE, date.plusDays(1), null),
                price("foreign", "foreign-club", "plan", PriceConcept.MONTHLY_FEE, date, null),
                price("different-plan", "club", "other-plan", PriceConcept.MONTHLY_FEE, date, null),
                price("incompatible", "club", "plan", PriceConcept.PACK, date, null)), date);
        assertThat(data.currentPrices()).extracting(SignupPlanData.CurrentPrice::id).containsExactly("current");
        assertThat(data.resolvedEntryFee(new Money(10000, "EUR"))).isEqualTo(new Money(10000, "EUR"));
        assertThat(data.name()).isEqualTo(text); assertThat(data.offerLabel()).isEqualTo(text);
        assertThat(data.description()).isEqualTo(text); assertThat(data.conditions()).isEqualTo(text);
        assertThat(data.billingMode()).isEqualTo("MONTHLY_FEE");
    }
    @Test void T_04_09_catalogProjectionResolvesExistingEntryModesAndPackTerms() {
        var standard = new Money(10000, "EUR");
        for (var fee : List.of(new EntryFee(EntryFeeMode.AMOUNT, new Money(0, "EUR"), null),
                new EntryFee(EntryFeeMode.NONE, null, null), new EntryFee(EntryFeeMode.PERCENT, null, 50),
                new EntryFee(EntryFeeMode.STANDARD, null, null))) {
            var data = SignupPlanData.from(plan(PlanType.MONTHLY, fee), List.of(), date);
            assertThat(data.resolvedEntryFee(standard)).isEqualTo(new Money(switch (fee.mode()) {
                case AMOUNT, NONE -> 0;
                case PERCENT -> 5000;
                case STANDARD -> 10000;
            }, "EUR"));
        }
        var pack = SignupPlanData.from(plan(PlanType.PACK, new EntryFee(EntryFeeMode.STANDARD, null, null)),
                List.of(price("pack-price", "club", "plan", PriceConcept.PACK, date, null)), date);
        assertThat(pack.pack()).isEqualTo(new SignupPlanData.Pack(6, 3));
        assertThat(pack.type()).isEqualTo(SignupPlanData.Type.PACK);
        assertThat(pack.resolvedEntryFee(standard)).isEqualTo(standard);
        assertThat(SignupPlanData.from(plan(PlanType.PACK, null), List.of(), date).resolvedEntryFee(standard)).isEqualTo(new Money(0, "EUR"));
        assertThat(pack.billingMode()).isNull();
        var original = plan(PlanType.SINGLE_CLASS, null);
        var noTexts = new Plan(original.id(), original.clubId(), original.code(), original.name(), original.type(), null,
                1, null, null, null, null, null, true, true, 0, true, 0, at, at, "actor", "actor");
        assertThat(SignupPlanData.from(noTexts, List.of(), date).offerLabel()).isNull();
    }
}
