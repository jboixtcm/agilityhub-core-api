package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.BillingSimulation;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S12 §6 reads of the monthly cycle (E8-T02): D6's month (`GET /billing/periods/{period}`: the simulation, the live run — or
 * the last rolled-back one — with `rollbackable` and `rollbackBlockers` computed now, its remittance, the chip counts), a run,
 * and the member's receipts of R-12-27 — their own and, with `FAMILY_GROUP`, those of the holder of the family group they
 * belong to (`familyGroup = true`). A receipt cancelled by a rollback is in neither the chip counts nor the member's
 * receipts (round 2, ruling E87).
 */
@Service
public class BillingQueries {
    public record Period(YearMonth period, BillingSimulation simulation, BillingRunService.RunOutcome run, Map<String, Long> counts) { }
    /** A member's receipt and whether it is the family holder's. */
    public record MemberInvoice(Invoice invoice, boolean familyGroup) { }
    public record MemberInvoices(List<MemberInvoice> items, int page, int size, long totalItems) { }

    private final BillingSimulationRepository simulations; private final BillingRunRepository runs; private final RemittanceRepository remittances;
    private final InvoiceRepository invoices; private final BillingRunService runService; private final BillingCensusAccess census; private final ClubConfigService configs;
    public BillingQueries(BillingSimulationRepository simulations, BillingRunRepository runs, RemittanceRepository remittances, InvoiceRepository invoices,
            BillingRunService runService, BillingCensusAccess census, ClubConfigService configs) {
        this.simulations = simulations; this.runs = runs; this.remittances = remittances; this.invoices = invoices; this.runService = runService;
        this.census = census; this.configs = configs;
    }

    public Period period(YearMonth period) {
        var simulation = simulations.forPeriod(period.toString()).orElse(null);
        var run = runs.latest(period.toString()).map(this::outcome).orElse(null);
        return new Period(period, simulation, run, invoices.countsByStatus(period.toString()));
    }
    public BillingRunService.RunOutcome run(String id) {
        return outcome(runs.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }
    private BillingRunService.RunOutcome outcome(BillingRun run) {
        Remittance remittance = remittances.forRun(run.id()).orElse(null);
        return new BillingRunService.RunOutcome(run, remittance, runService.blockers(run));
    }

    /** R-12-27: the members whose receipts {@code memberId} reads — itself, then its family group's holder. */
    public List<String> readableMembers(String memberId) {
        var members = new ArrayList<String>(); members.add(memberId);
        if (configs.get(TenantContext.require()).modules().contains(Module.FAMILY_GROUP)) {
            census.familyGroupOf(memberId).map(BillingCensusAccess.FamilyGroup::holderMemberId)
                    .filter(holder -> holder != null && !holder.equals(memberId)).ifPresent(members::add);
        }
        return members;
    }
    public MemberInvoices memberInvoices(String memberId, int page, int size) {
        var members = readableMembers(memberId);
        var items = invoices.ofMembers(members, page, size).stream().map(invoice -> new MemberInvoice(invoice, !invoice.memberId().equals(memberId))).toList();
        return new MemberInvoices(items, page, size, invoices.countOfMembers(members));
    }
    /**
     * One of the receipts {@code memberId} reads; another member's (or another club's) is `404`, and so is one cancelled by a
     * rollback (R-12-14, ruling E87: the run that issued it never happened for the member; its number is reissued).
     */
    public MemberInvoice memberInvoice(String memberId, String invoiceId) {
        var invoice = invoices.findById(invoiceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!readableMembers(memberId).contains(invoice.memberId()) || invoices.rolledBack(invoice)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return new MemberInvoice(invoice, !invoice.memberId().equals(memberId));
    }
}
