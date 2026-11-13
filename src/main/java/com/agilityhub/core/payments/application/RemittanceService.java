package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.ExportFileStore;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * S12 §6 remittances of D6 «Remeses» (E8-T03; R-12-12, R-12-14, R-12-15), the open club's only (another club's → 404).
 *
 * <ul>
 * <li>{@link #file}: a link of {@value #LINK_MINUTES} minutes to the pain.008 file, a rolled-back remittance's too (its file is
 * kept, R-12-14), named `remesa-{period}.xml`. Issuing it is the file access, audited `DATA_EXPORTED` (S14 R-14-09: a read is
 * audited only when it exports); on S3 the download itself never reaches the api.</li>
 * <li>{@link #download}: the local store's signed route (CONVENCIONS_API §5, A31), which authorises itself: the signature over
 * the club, the route and the expiry is checked first, then the club is opened as the tenant.</li>
 * <li>{@link #submit}: R-12-15 [Marca com a enviada al banc], `GENERATED → SUBMITTED` with the club-local day the file went to the
 * bank (not after today, not before the remittance's own day; stored as that day's start in the club's zone) and the admin
 * who marked it, audited `REMITTANCE_SUBMITTED`; any other status is `409 INVALID_STATE`. From then on the run cannot be
 * rolled back (`RUN_NOT_ROLLBACKABLE {reasons: [REMITTANCE_SUBMITTED]}`) and an unpaid debit follows R-12-17.</li>
 * <li>{@link #undoSubmission}: SEPA-04 (a), R-12-15 [Desfés «enviada al banc»], `SUBMITTED → GENERATED` with the admin's reason,
 * audited `REMITTANCE_SUBMISSION_UNDONE`: only before the remittance's collection date and while every receipt it carries is as
 * the run left it.</li>
 * </ul>
 */
@Service
public class RemittanceService {
    static final int LINK_MINUTES = 5;
    public record FileLink(String downloadUrl, String fileName, Instant expiresAt) { }
    public record Download(InputStream content, String fileName, String contentType) { }

    private final RemittanceRepository remittances; private final InvoiceRepository invoices; private final CollectionRepository collections;
    private final ExportFileStore files; private final ClubConfigService configs; private final AuditWriter audit; private final Clock clock;
    public RemittanceService(RemittanceRepository remittances, InvoiceRepository invoices, CollectionRepository collections, ExportFileStore files,
            ClubConfigService configs, AuditWriter audit, Clock clock) {
        this.remittances = remittances; this.invoices = invoices; this.collections = collections; this.files = files; this.configs = configs;
        this.audit = audit; this.clock = clock;
    }

    public Remittance get(String id) { return remittances.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }

    public FileLink file(String id) {
        var remittance = get(id);
        if (remittance.fileKey() == null) { throw new ApiException(ErrorCode.NOT_FOUND); }
        String club = TenantContext.require(), name = fileName(remittance);
        Instant expires = clock.instant().plus(Duration.ofMinutes(LINK_MINUTES));
        String url = files.downloadUrl(remittance.fileKey(), name, club, localPath(club, id), expires);
        audit.write(new AuditCommand(AuditAction.DATA_EXPORTED, "Remittance", id, null, null, null, null),
                Map.of("file", name, "messageId", remittance.messageId(), "period", remittance.period()));
        return new FileLink(url, name, expires);
    }

    public byte[] workbook(Remittance remittance, org.springframework.context.MessageSource messages, java.util.Locale locale) {
        if (remittance.fileKey() == null) { throw new ApiException(ErrorCode.NOT_FOUND); }
        try (var xml = files.open(remittance.fileKey())) {
            byte[] bytes = com.agilityhub.core.payments.application.sepa.RemittanceWorkbook.render(xml, messages, locale);
            audit.write(new AuditCommand(AuditAction.DATA_EXPORTED, "Remittance", remittance.id(), null, null, null, null),
                    Map.of("format", "xlsx", "messageId", remittance.messageId(), "period", remittance.period()));
            return bytes;
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    public Download download(String club, String id, long expires, String signature) {
        files.verifyLocal(club, localPath(club, id), expires, signature);
        try (var tenant = TenantContext.open(club)) {
            var remittance = remittances.findById(id).filter(found -> found.fileKey() != null).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            return new Download(files.open(remittance.fileKey()), fileName(remittance), "application/xml");
        }
    }

    public Remittance submit(String id, LocalDate submittedAt) {
        var remittance = get(id);
        if (remittance.status() != RemittanceStatus.GENERATED) { throw new ApiException(ErrorCode.INVALID_STATE); }
        ZoneId zone = ZoneId.of(configs.get(TenantContext.require()).club().timeZone());
        if (submittedAt.isAfter(LocalDate.ofInstant(clock.instant(), zone)) || submittedAt.isBefore(LocalDate.ofInstant(remittance.creationAt(), zone))) {
            throw BillingContractAccess.invalid("submittedAt");
        }
        if (!remittances.submit(id, submittedAt.atStartOfDay(zone).toInstant(), BillingEvents.actor())) { throw new ApiException(ErrorCode.INVALID_STATE); }
        var after = get(id);
        audit.write(new AuditCommand(AuditAction.REMITTANCE_SUBMITTED, "Remittance", id, null, remittance, after, null),
                Map.of("runId", remittance.runId(), "period", remittance.period(), "messageId", remittance.messageId(), "submittedOn", submittedAt.toString()));
        return after;
    }

    /**
     * SEPA-04 (a), R-12-15 [Desfés «enviada al banc»]: the file did not reach the bank (it refused it, or the remittance was marked
     * by mistake), so `SUBMITTED → GENERATED`, its `submittedAt` and `submittedByAccountId` removed. Allowed only before the
     * remittance's collection date (club-local) — `409 INVALID_STATE {reason: COLLECTION_DATE_PASSED}` — and while every receipt it
     * carries is as the run left it: `COLLECTING` in this remittance, its attempt here still open and its last one (none returned,
     * paid, cancelled or charged by card since) — `409 INVALID_STATE {reason: INVOICE_CHANGED, invoiceIds}`. Not `SUBMITTED` →
     * `409 INVALID_STATE {status}`. Audited `REMITTANCE_SUBMISSION_UNDONE` with the admin's reason. The run can then be rolled
     * back (R-12-14), and D6 asks again to mark the remittance sent.
     */
    public Remittance undoSubmission(String id, String reason) {
        var remittance = get(id);
        if (remittance.status() != RemittanceStatus.SUBMITTED) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("status", remittance.status().name())); }
        ZoneId zone = ZoneId.of(configs.get(TenantContext.require()).club().timeZone());
        if (!LocalDate.ofInstant(clock.instant(), zone).isBefore(LocalDate.parse(remittance.requestedCollectionDate()))) {
            throw new ApiException(ErrorCode.INVALID_STATE, Map.of("reason", "COLLECTION_DATE_PASSED"));
        }
        var changed = changedInvoices(remittance);
        if (!changed.isEmpty()) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("reason", "INVOICE_CHANGED", "invoiceIds", changed)); }
        if (!remittances.undoSubmission(id)) { throw new ApiException(ErrorCode.STALE_VERSION); }
        var after = get(id);
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("runId", remittance.runId()); details.put("period", remittance.period()); details.put("messageId", remittance.messageId());
        if (remittance.submittedAt() != null) { details.put("submittedOn", LocalDate.ofInstant(remittance.submittedAt(), zone).toString()); }
        audit.write(new AuditCommand(AuditAction.REMITTANCE_SUBMISSION_UNDONE, "Remittance", id, null, remittance, after, reason), details);
        return after;
    }
    /** The receipts of {@code remittance} that something touched since the run: not as {@link #undoSubmission} requires. */
    private List<String> changedInvoices(Remittance remittance) {
        var changed = new TreeSet<String>();
        for (String collectionId : remittance.collectionIds()) {
            var attempt = collections.findById(collectionId).orElse(null);
            if (attempt == null) { continue; }
            var invoice = invoices.findById(attempt.invoiceId()).orElse(null);
            var attempts = collections.forInvoice(attempt.invoiceId());
            boolean open = attempt.status() == CollectionStatus.CREATED || attempt.status() == CollectionStatus.SUBMITTED;
            boolean last = !attempts.isEmpty() && attempts.getLast().id().equals(attempt.id());
            if (invoice == null || invoice.status() != InvoiceStatus.COLLECTING || !remittance.id().equals(invoice.remittanceId()) || !open || !last) {
                changed.add(attempt.invoiceId());
            }
        }
        return List.copyOf(changed);
    }

    /** `remesa-{period}.xml` (S12 §2 «Descarrega l'XML»). */
    static String fileName(Remittance remittance) { return "remesa-" + remittance.period() + ".xml"; }
    /** The local store's self-authorising route of a remittance's file (see {@link #download}). */
    public static String localPath(String club, String id) { return "/api/v1/remittances/files/" + club + "/" + id; }
}
