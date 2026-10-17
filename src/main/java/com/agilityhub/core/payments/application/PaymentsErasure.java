package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.application.ports.PaymentPrivacyPort;
import com.agilityhub.core.payments.persistence.PaymentsErasureRepository;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.privacy.ErasureBlockReason;
import com.agilityhub.core.platform.application.privacy.ErasureCheck;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import com.agilityhub.core.platform.application.privacy.RetentionTask;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.ExportFileStore;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * S14 WP-14-D, the payments side: R-14-14's `INVOICES_OPEN` (an invoice `PENDING`, `COLLECTING` or `FAILED`) and `UPFRONT_OPEN`
 * (`DUE`, `PARTIAL`, `CHECKOUT_PENDING`); R-14-15's invoice snapshots and S12's `forgetCustomer` for a card member (queued in the
 * erasure's transaction, before the census drops the customer reference: this step runs first); and R-14-16 (c), the remittance
 * XML files older than `rgpd.retentionYearsAfterLeave`.
 */
@Component
@Order(10)
public class PaymentsErasure implements ErasureCheck, ErasureStep, RetentionTask, MemberDataSource {
    private final PaymentsErasureRepository erasure;
    private final BillingCensusAccess census;
    private final PaymentPrivacyPort privacy;
    private final ExportFileStore files;
    public PaymentsErasure(PaymentsErasureRepository erasure, BillingCensusAccess census, PaymentPrivacyPort privacy, ExportFileStore files) {
        this.erasure = erasure; this.census = census; this.privacy = privacy; this.files = files;
    }

    @Override public Set<ErasureBlockReason> blockReasons(Collection<String> memberIds, Instant now) {
        var reasons = EnumSet.noneOf(ErasureBlockReason.class);
        if (erasure.openInvoices(memberIds)) { reasons.add(ErasureBlockReason.INVOICES_OPEN); }
        if (erasure.openUpfrontPayments(memberIds)) { reasons.add(ErasureBlockReason.UPFRONT_OPEN); }
        return reasons;
    }

    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        for (var member : subject.members()) {
            if (member.erased()) { continue; }
            census.card(member.id()).map(BillingCensusAccess.Card::customerId).ifPresent(privacy::forgetCustomer);
            String label = subject.memberLabel(member.id());
            effect.count("invoices", erasure.eraseInvoices(member.id(), label));
            effect.count("billing_runs+billing_simulations", erasure.eraseBillingNames(member.id(), label));
        }
        return effect.count("checkout_sessions", erasure.eraseCheckoutRequests(subject.memberIds())).build();
    }

    @Override public String entityType() { return "Remittance"; }
    @Override public String action() { return "DELETE_REMITTANCE_FILE"; }
    @Override public String counter() { return "remittanceFilesDeleted"; }
    @Override public List<String> due(JobContext context) {
        int years = context.parameter("rgpd.retentionYearsAfterLeave", Integer.class);
        return erasure.remittanceFilesBefore(context.localDate().minusYears(years).atStartOfDay(context.zone()).toInstant());
    }
    @Override public boolean apply(JobContext context, String remittanceId) {
        String key = erasure.remittanceFile(remittanceId);
        if (key == null) { return false; }
        files.delete(key);
        return erasure.remittanceFilePurged(remittanceId, context.scheduledFor());
    }

    /** S14 R-14-13: `invoices[]` with their lines and collections, `upfrontPayments[]` and `packBalances[]`. */
    @Override public void contribute(ErasureMember member, MemberDataPackage data) {
        var invoices = erasure.rows("invoices", "memberId", List.of(member.id()));
        var collections = erasure.rows("collections", "invoiceId", invoices.stream().map(invoice -> invoice.getString("_id")).toList());
        data.section("invoices", invoices.stream().map(invoice -> {
            var row = MemberDataPackage.row(invoice);
            row.put("collections", collections.stream().filter(collection -> invoice.getString("_id").equals(collection.getString("invoiceId")))
                    .map(MemberDataPackage::row).toList());
            return (Object) row;
        }).toList());
        data.section("upfrontPayments", erasure.rows("upfront_payments", "memberId", List.of(member.id())).stream().map(MemberDataPackage::row).toList());
        data.section("packBalances", erasure.rows("pack_balances", "memberId", List.of(member.id())).stream().map(MemberDataPackage::row).toList());
    }
}
