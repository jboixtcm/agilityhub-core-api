package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.clubs.catalogs.application.SignupPlanData;
import com.agilityhub.core.clubs.catalogs.application.SignupPlanData.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static com.agilityhub.core.clubs.signup.domain.SignupContactsTest.error;
import static com.agilityhub.core.clubs.signup.domain.FirstMonthCalculator.Option.*;
import static com.agilityhub.core.clubs.signup.domain.UpfrontLines.Concept.*;

class SignupPaymentsTest {
    private final SignupParameters defaults = SignupParameters.resolve(new ParameterCatalog(new ObjectMapper()), key -> null);
    private final LocalDate today = LocalDate.of(2026, 8, 17);
    private final Set<Module> all = Set.of(Module.BILLING, Module.PACKS, Module.SINGLE_CLASS);
    private static Money eur(long minor) { return new Money(minor, "EUR"); }
    private SignupPlanData plan(String id, Type type, Entry entry, List<CurrentPrice> prices) {
        return new SignupPlanData(id, "club", type, type == Type.MONTHLY ? "MONTHLY_FEE" : null, 1,
                new LocalizedText(Map.of("ca", id), "ca"), null, null, null, true, true, 10,
                entry, type == Type.PACK ? new Pack(6, 3) : null, prices);
    }
    private SignupPlanCatalog.Offer offer(SignupPlanData plan) {
        return SignupPlanCatalog.list("club", List.of(plan), all, defaults.entryFeePerDog()).getFirst();
    }
    private SignupPlanCatalog.Offer monthly() {
        return offer(plan("monthly", Type.MONTHLY, null, List.of(new CurrentPrice("price", Concept.MONTHLY_FEE, eur(6000)))));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-08-17,6000,3000,2026-08-17,2026-09-01,6000,2026-09-01,2026-10-01",
        "2026-08-05,6000,6000,2026-08-05,2026-09-01,3000,2026-08-16,2026-09-01",
        "2026-08-16,6000,3000,2026-08-16,2026-09-01,6000,2026-09-01,2026-10-01",
        "2026-12-31,6000,3000,2026-12-31,2027-01-01,6000,2027-01-01,2027-02-01",
        "2026-08-17,6500,3250,2026-08-17,2026-09-01,6500,2026-09-01,2026-10-01",
        "2026-08-17,5999,3000,2026-08-17,2026-09-01,5999,2026-09-01,2026-10-01",
        "2026-08-17,6001,3001,2026-08-17,2026-09-01,6001,2026-09-01,2026-10-01"
    })
    void T_04_05_firstMonthTableUsesHalfUpAndStartMonthForNextInvoice(LocalDate date, long price,
            long a, LocalDate startA, LocalDate invoiceA, long b, LocalDate startB, LocalDate invoiceB) {
        var options = FirstMonthCalculator.options(date, 16, 1, eur(price));
        assertThat(options).extracting(FirstMonthCalculator.Choice::option).containsExactly(TODAY, ALTERNATIVE);
        assertThat(options).extracting(c -> c.amountDue().amountMinor()).containsExactly(a, b);
        assertThat(options).extracting(FirstMonthCalculator.Choice::startDate).containsExactly(startA, startB);
        assertThat(options).extracting(FirstMonthCalculator.Choice::nextInvoiceDate).containsExactly(invoiceA, invoiceB);
    }
    @Test void T_04_05_clockUsesClubZoneAndCatalogOverrides() {
        var clock = Clock.fixed(Instant.parse("2026-08-15T22:30:00Z"), ZoneOffset.UTC);
        var madrid = FirstMonthCalculator.options(clock, ZoneId.of("Europe/Madrid"), defaults, eur(6000)).getFirst();
        var argentina = FirstMonthCalculator.options(clock, ZoneId.of("America/Argentina/Buenos_Aires"), defaults, eur(6000)).getFirst();
        assertThat(madrid.startDate()).isEqualTo("2026-08-16");
        assertThat(madrid.portion()).isEqualTo(FirstMonthCalculator.Portion.HALF_MONTH);
        assertThat(argentina.startDate()).isEqualTo("2026-08-15");
        assertThat(argentina.portion()).isEqualTo(FirstMonthCalculator.Portion.FULL_MONTH);
        var overrides = Map.<String,Object>of("signup.firstMonthSplitDay", 10, "billing.entryFeePerDog", eur(12345));
        var changed = SignupParameters.resolve(new ParameterCatalog(new ObjectMapper()), overrides::get);
        assertThat(changed.entryFeePerDog()).isEqualTo(eur(12345));
        assertThat(FirstMonthCalculator.options(LocalDate.of(2026, 8, 12), changed.firstMonthSplitDay(), 1, eur(6000)).getFirst().amountDue()).isEqualTo(eur(3000));
        assertThat(FirstMonthCalculator.nextInvoice(LocalDate.of(2026, 1, 31), 31)).isEqualTo("2026-02-28");
        assertThat(FirstMonthCalculator.nextInvoice(LocalDate.of(2028, 1, 31), 31)).isEqualTo("2028-02-29");
        assertThat(FirstMonthCalculator.options(LocalDate.of(2026, 2, 28), 31, 31, eur(6000)).getFirst().portion())
                .isEqualTo(FirstMonthCalculator.Portion.HALF_MONTH);
        error(() -> FirstMonthCalculator.options(today, 0, 1, eur(6000)), ErrorCode.PARAMETER_INVALID);
        error(() -> FirstMonthCalculator.options(today, 16, 32, eur(6000)), ErrorCode.PARAMETER_INVALID);
        error(() -> FirstMonthCalculator.options(today, 16, 1, eur(-1)), ErrorCode.VALIDATION_ERROR);
        error(() -> FirstMonthCalculator.options(today, 16, 1, null), ErrorCode.VALIDATION_ERROR);
        for (int bad : new int[]{-1, 101}) {
            error(() -> new SignupParameters(16, 1, 25, bad, eur(100)), ErrorCode.PARAMETER_INVALID);
        }
    }
    @Test void T_04_06_publicUpfrontLinesFollowPlanTypesAndBilling() {
        var quote = UpfrontLines.publicSignup(monthly(), "dog", true, today, defaults, TODAY);
        assertThat(quote.lines()).extracting(UpfrontLines.Line::concept).containsExactly(ENTRY_FEE, FIRST_MONTH);
        assertThat(quote.lines()).extracting(l -> l.amountDue().amountMinor()).containsExactly(10000L, 3000L);
        assertThat(quote.firstMonth().startDate()).isEqualTo(today);
        assertThat(quote.totalDue()).isEqualTo(eur(13000));
        var pack = offer(plan("pack", Type.PACK, null, List.of(new CurrentPrice("pack-price", Concept.PACK, eur(13500)))));
        assertThat(UpfrontLines.publicSignup(pack, "dog", true, today, defaults, TODAY).lines())
                .containsExactly(new UpfrontLines.Line(PACK, "dog", eur(13500)));
        var therapy = offer(plan("therapy", Type.MONTHLY, new Entry(null, 50, false),
                List.of(new CurrentPrice("therapy-price", Concept.MAINTENANCE_FEE, eur(1000)))));
        assertThat(therapy.maintenanceFee()).isEqualTo(eur(1000));
        assertThat(UpfrontLines.publicSignup(therapy, "dog", true, today, defaults, TODAY).lines())
                .containsExactly(new UpfrontLines.Line(ENTRY_FEE, "dog", eur(5000)));
        assertThat(UpfrontLines.publicSignup(monthly(), "dog", false, today, defaults, TODAY).lines()).isEmpty();
        assertThat(UpfrontLines.publicSignup(null, "dog", true, today, defaults, TODAY).totalDue()).isEqualTo(eur(0));
        var zero = offer(plan("free", Type.MONTHLY, new Entry(eur(0), null, false), List.of()));
        assertThat(UpfrontLines.publicSignup(zero, "dog", true, today, defaults, TODAY).lines()).isEmpty();
        var single = offer(plan("single", Type.SINGLE_CLASS, new Entry(eur(100), null, false),
                List.of(new CurrentPrice("single-price", Concept.SINGLE_CLASS, eur(2000)))));
        assertThat(UpfrontLines.publicSignup(single, "dog", true, today, defaults, TODAY).lines())
                .containsExactly(new UpfrontLines.Line(ENTRY_FEE, "dog", eur(100)));
        var packWithEntry = offer(plan("entry-pack", Type.PACK, new Entry(eur(500), null, false),
                List.of(new CurrentPrice("p", Concept.PACK, eur(13500)))));
        assertThat(UpfrontLines.publicSignup(packWithEntry, "dog", true, today, defaults, TODAY).totalDue()).isEqualTo(eur(14000));
        assertThat(UpfrontLines.publicSignup(offer(plan("empty-pack", Type.PACK, null, List.of())), "dog", true, today, defaults, TODAY).lines()).isEmpty();
        var maintenanceData = plan("maintenance", Type.MONTHLY, null, List.of(new CurrentPrice("p", Concept.MONTHLY_FEE, eur(6000))));
        var maintenance = new SignupPlanData(maintenanceData.id(), "club", Type.MONTHLY, "MAINTENANCE", 1,
                maintenanceData.name(), null, null, null, true, true, 1, null, null, maintenanceData.currentPrices());
        assertThat(UpfrontLines.publicSignup(offer(maintenance), "dog", true, today, defaults, TODAY).firstMonth()).isNull();
        error(() -> UpfrontLines.publicSignup(monthly(), "dog", true, today, defaults, null), ErrorCode.VALIDATION_ERROR);
    }
    @Test void T_04_06_addDogUsesCurrentMonthDeltaAndInclusiveCutoff() {
        var date = LocalDate.of(2026, 8, 12);
        var quote = UpfrontLines.addDog(monthly(), "new-dog", true, date, defaults, eur(3000), TODAY);
        assertThat(quote.lines()).containsExactly(new UpfrontLines.Line(ENTRY_FEE, "new-dog", eur(10000)),
                new UpfrontLines.Line(ADDITIONAL_DOG_FEE, "new-dog", eur(3000)));
        assertThat(quote.additionalDog()).isEqualTo(new UpfrontLines.Period(TODAY, date, eur(3000)));
        for (int day : new int[]{12, 25}) {
            var later = UpfrontLines.addDog(monthly(), "new-dog", true, date.withDayOfMonth(day), defaults, eur(3000), ALTERNATIVE);
            assertThat(later.lines()).containsExactly(new UpfrontLines.Line(ENTRY_FEE, "new-dog", eur(10000)));
            assertThat(later.additionalDog().startDate()).isEqualTo("2026-09-01");
        }
        assertThat(UpfrontLines.additionalDogOptions(date.withDayOfMonth(26), eur(3000), 25)).hasSize(1);
        error(() -> UpfrontLines.addDog(monthly(), "dog", true, date.withDayOfMonth(26), defaults, eur(3000), ALTERNATIVE), ErrorCode.VALIDATION_ERROR);
        assertThat(UpfrontLines.addDog(monthly(), "dog", false, date, defaults, null, TODAY).lines()).isEmpty();
        assertThat(UpfrontLines.addDog(null, "dog", true, date, defaults, null, TODAY).lines()).isEmpty();
        assertThat(UpfrontLines.additionalMonthlyFee(eur(6000), eur(9000), eur(6000), 50)).isEqualTo(eur(3000));
        assertThat(UpfrontLines.additionalMonthlyFee(eur(6000), eur(9500), eur(6000), 50)).isEqualTo(eur(3500));
        assertThat(UpfrontLines.additionalMonthlyFee(eur(6000), eur(5000), eur(6000), 50)).isEqualTo(eur(0));
        assertThat(UpfrontLines.additionalMonthlyFee(null, null, eur(6000), 50)).isEqualTo(eur(3000));
        assertThat(UpfrontLines.additionalMonthlyFee(null, eur(9000), eur(6000), 25)).isEqualTo(eur(4500));
        for (int bad : new int[]{-1, 101}) {
            error(() -> UpfrontLines.additionalMonthlyFee(null, null, eur(6000), bad), ErrorCode.VALIDATION_ERROR);
        }
    }
    @ParameterizedTest
    @CsvSource({"13000,10000,PAID,3000,PAID","10000,10000,PAID,0,DUE","5000,5000,PARTIAL,0,DUE","0,0,DUE,0,DUE"})
    void T_04_07_allocationCascadesEntryBeforeFirstMonth(long paid, long entryPaid, UpfrontPaymentStatus entryStatus,
            long monthPaid, UpfrontPaymentStatus monthStatus) {
        var lines = List.of(new UpfrontLines.Line(FIRST_MONTH, "dog", eur(3000)), new UpfrontLines.Line(ENTRY_FEE, "dog", eur(10000)));
        assertThat(UpfrontAllocator.allocate(lines, eur(paid))).containsExactly(
                new UpfrontAllocator.Allocation(lines.get(1), eur(entryPaid), entryStatus),
                new UpfrontAllocator.Allocation(lines.get(0), eur(monthPaid), monthStatus));
    }
    @Test void T_04_07_allocationRejectsExcessNegativeAndMixedCurrency() {
        var lines = List.of(new UpfrontLines.Line(ENTRY_FEE, "dog", eur(10000)), new UpfrontLines.Line(FIRST_MONTH, "dog", eur(3000)));
        error(() -> UpfrontAllocator.allocate(lines, eur(13100)), ErrorCode.UPFRONT_AMOUNT_EXCEEDS_DUE);
        error(() -> UpfrontAllocator.allocate(lines, eur(-1)), ErrorCode.VALIDATION_ERROR);
        error(() -> UpfrontAllocator.allocate(lines, new Money(100, "USD")), ErrorCode.CURRENCY_MISMATCH);
        assertThat(UpfrontAllocator.allocate(List.of(), eur(0))).isEmpty();
        assertThat(UpfrontAllocator.allocate(List.of(new UpfrontLines.Line(PACK, "dog", eur(0))), eur(0)).getFirst().status())
                .isEqualTo(UpfrontPaymentStatus.PAID);
    }
    @Test void T_04_09_catalogFiltersTypesVisibilityTenantAndSuppressesAmounts() {
        var monthly = plan("monthly", Type.MONTHLY, null, List.of(new CurrentPrice("monthly-price", Concept.MONTHLY_FEE, eur(6000))));
        var pack = plan("pack", Type.PACK, null, List.of(new CurrentPrice("pack-price", Concept.PACK, eur(13500))));
        var single = plan("single", Type.SINGLE_CLASS, null, List.of());
        var therapy = plan("therapy", Type.MONTHLY, new Entry(null, 50, false), List.of(new CurrentPrice("m", Concept.MAINTENANCE_FEE, eur(1000))));
        var hidden = new SignupPlanData("hidden", "club", Type.MONTHLY, "MONTHLY_FEE", 1, monthly.name(), null, null, null, true, false, 0, null, null, List.of());
        var inactive = new SignupPlanData("inactive", "club", Type.MONTHLY, "MONTHLY_FEE", 1, monthly.name(), null, null, null, false, true, 0, null, null, List.of());
        var foreign = new SignupPlanData("foreign", "other-club", Type.MONTHLY, "MONTHLY_FEE", 1, monthly.name(), null, null, null, true, true, 0, null, null, List.of());
        var plans = List.of(therapy, pack, monthly, single, hidden, inactive, foreign);
        assertThat(SignupPlanCatalog.list("club", plans, Set.of(Module.BILLING), eur(10000)))
                .extracting(SignupPlanCatalog.Offer::id).containsExactly("monthly", "therapy");
        var offers = SignupPlanCatalog.list("club", plans, all, eur(10000));
        assertThat(offers).extracting(SignupPlanCatalog.Offer::entryFee).containsExactly(eur(10000), eur(0), eur(0), eur(5000));
        assertThat(SignupPlanCatalog.require(offers, "pack").pack()).isEqualTo(new Pack(6, 3));
        var noBilling = SignupPlanCatalog.list("club", plans, Set.of(Module.PACKS, Module.SINGLE_CLASS), null);
        assertThat(noBilling).hasSize(4).allSatisfy(o -> {
            assertThat(o.price()).isNull(); assertThat(o.entryFee()).isNull(); assertThat(o.maintenanceFee()).isNull();
        });
        assertThat(offer(plan("none", Type.MONTHLY, new Entry(null, null, true), List.of())).entryFee()).isEqualTo(eur(0));
        error(() -> SignupPlanCatalog.require(offers, "hidden"), ErrorCode.PLAN_NOT_AVAILABLE);
        error(() -> offer(plan("foreign-fee", Type.MONTHLY, new Entry(new Money(100, "USD"), null, false), List.of())), ErrorCode.CURRENCY_MISMATCH);
        assertThat(SignupPlanCatalog.list("club", List.of(), all, eur(10000))).isEmpty();
    }
}
