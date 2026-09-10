package com.agilityhub.core.clubs.dashboard.domain;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.shared.application.contract.SignupWarning;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class PendingSignupBuilder {
    private PendingSignupBuilder() { }
    public record Result(PendingKpi kpi, PendingSignups card) { }
    public static Result build(List<PendingSource> sources, DashboardPeriod period, int warnDays,
            boolean billing, boolean family, String locale, String defaultLocale) {
        var items = sources.stream().sorted(Comparator.comparing(PendingSource::submittedAt).thenComparing(PendingSource::memberId))
                .map(s -> new PendingItem(s.memberId(), shortName(s.firstName(), s.lastName()), s.dogs(),
                        s.planName() == null ? "—" : s.planName().withDefaultLocale(defaultLocale).resolve(locale).value(),
                        billing ? s.paymentMethodType() : null, billing ? warnings(s, family) : null, s.submittedAt(),
                        Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(s.submittedAt().atZone(period.zone()).toLocalDate(), period.today())))))
                .toList();
        return new Result(new PendingKpi(items.size(), (int) items.stream().filter(i -> i.pendingDays() > warnDays).count(), warnDays),
                new PendingSignups(items.size(), items));
    }
    private static String shortName(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last.strip().substring(0, last.strip().offsetByCodePoints(0, 1)) + ".";
    }
    private static List<SignupWarning> warnings(PendingSource source, boolean family) {
        var warnings = new ArrayList<SignupWarning>();
        if (!source.imageConsent()) { warnings.add(SignupWarning.NO_IMAGE_CONSENT); }
        if ("SEPA_DD".equals(source.paymentMethodType()) && !source.accountProvided()) { warnings.add(SignupWarning.ACCOUNT_NOT_PROVIDED); }
        if (source.documentPending()) { warnings.add(SignupWarning.DOCUMENT_PENDING); }
        if (family && source.familyPending()) { warnings.add(SignupWarning.FAMILY_HOLDER_NOT_FOUND); }
        if (source.upfrontUnpaid()) { warnings.add(SignupWarning.UPFRONT_UNPAID); }
        if (source.readmission()) { warnings.add(SignupWarning.READMISSION); }
        return List.copyOf(warnings);
    }
}
