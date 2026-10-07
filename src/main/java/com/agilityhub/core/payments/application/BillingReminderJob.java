package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Component;

/** P10 only reminds admins: no receipt and no remittance is ever generated automatically at R1. */
@Component
public class BillingReminderJob implements Job {
    private final BillingCensusAccess census; private final RemittanceRepository remittances;
    private final JobActionMarks marks; private final BillingEvents events;
    private final com.agilityhub.core.platform.application.CensusClubSettings settings;
    public BillingReminderJob(BillingCensusAccess census, RemittanceRepository remittances, JobActionMarks marks, BillingEvents events,
            com.agilityhub.core.platform.application.CensusClubSettings settings) {
        this.census = census; this.remittances = remittances; this.marks = marks; this.events = events;
        this.settings = settings;
    }
    @Override public JobName name() { return JobName.BILLING_REMINDER; }
    @Override public List<JobItem> plan(JobContext context) {
        context.recorder().count("remittanceReminders", 0);
        int day = context.parameter("billing.remittanceReminderDay", Integer.class);
        if (day == 0 || context.localDate().getDayOfMonth() != day) { return List.of(); }
        if (!settings.providerEnabled("SEPA_XML")) { return List.of(); }
        var period = YearMonth.from(context.localDate()).plusMonths(1);
        if (marks.contains("REMIND_BILLING", period.toString()) || remittances.forPeriod(period.toString()).stream().anyMatch(r -> r.status() != RemittanceStatus.ROLLED_BACK)) { return List.of(); }
        long pending = census.activeMembers().stream().filter(m -> m.paymentMethod() != null && "SEPA_DD".equals(m.paymentMethod().type())
                && m.nextInvoiceDate() != null && !m.nextInvoiceDate().isAfter(period.atEndOfMonth())).count();
        return pending == 0 ? List.of() : List.of(new JobItem("Club", context.clubId(), "REMIND_BILLING", Map.of("period", period.toString(), "pendingMembers", pending)));
    }
    @Override public JobEffect apply(JobContext context, JobItem item) {
        var current = plan(context);
        if (current.isEmpty()) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        var reminder = current.getFirst(); marks.record("REMIND_BILLING", reminder.detail().get("period").toString());
        events.publish(BillingEvent.Kind.RemittanceReminderDue, context.clubId(), reminder.detail());
        return new JobEffect(reminder.action(), reminder.detail(), Map.of("remittanceReminders", 1L));
    }
}
