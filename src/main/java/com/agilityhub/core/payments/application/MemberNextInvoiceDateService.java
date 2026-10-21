package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S12 §6 `PUT /members/{id}/next-invoice-date` (E11-T15 step 3, ruling E146; R-12-01, R-12-05, R-12-06; S03 §3): the admin's
 * correction of the date the next run bills a member from — a migrated cash member Playoff already charged to the end of its
 * half-year, a member left without a date (`NO_NEXT_INVOICE_DATE`). The date is any day from the first of the club's current
 * month {@value #MONTHS} months back to the last day of the month {@value #MONTHS} months ahead (a catch-up of unbilled months
 * reads `month(nextInvoiceDate)`, R-12-01/02); else `400 VALIDATION_ERROR {field: nextInvoiceDate}`. The census writes it
 * (`ACTIVE` only, `MemberUpdated{diff}`); the change is audited `MEMBER_UPDATED` with the before/after date and the admin's
 * reason, as a census edit (`MemberService.patch`) is — no new audit action or event. The same date again changes nothing and
 * writes no entry.
 */
@Service
public class MemberNextInvoiceDateService {
    static final int MONTHS = 12;
    private final BillingCensusAccess census; private final AuditWriter audit; private final ClubClock clock;
    public MemberNextInvoiceDateService(BillingCensusAccess census, AuditWriter audit, ClubClock clock) {
        this.census = census; this.audit = audit; this.clock = clock;
    }

    @Transactional
    public LocalDate set(String memberId, LocalDate date, String reason) {
        var month = YearMonth.from(clock.today(TenantContext.require()));
        if (date.isBefore(month.minusMonths(MONTHS).atDay(1)) || date.isAfter(month.plusMonths(MONTHS).atEndOfMonth())) {
            throw BillingContractAccess.invalid("nextInvoiceDate");
        }
        census.setNextInvoiceDate(memberId, date).ifPresent(change -> audit.write(new AuditCommand(AuditAction.MEMBER_UPDATED, "Member", memberId, memberId,
                dated(change.before()), dated(change.after()), reason)));
        return date;
    }
    private static Map<String, Object> dated(LocalDate date) {
        var fields = new LinkedHashMap<String, Object>(); fields.put("nextInvoiceDate", date == null ? null : date.toString()); return fields;
    }
}
