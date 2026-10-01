package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.RemittanceStatus;
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
import java.util.Map;
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
 * </ul>
 */
@Service
public class RemittanceService {
    static final int LINK_MINUTES = 5;
    public record FileLink(String downloadUrl, String fileName, Instant expiresAt) { }
    public record Download(InputStream content, String fileName, String contentType) { }

    private final RemittanceRepository remittances; private final ExportFileStore files; private final ClubConfigService configs;
    private final AuditWriter audit; private final Clock clock;
    public RemittanceService(RemittanceRepository remittances, ExportFileStore files, ClubConfigService configs, AuditWriter audit, Clock clock) {
        this.remittances = remittances; this.files = files; this.configs = configs; this.audit = audit; this.clock = clock;
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

    /** `remesa-{period}.xml` (S12 §2 «Descarrega l'XML»). */
    static String fileName(Remittance remittance) { return "remesa-" + remittance.period() + ".xml"; }
    /** The local store's self-authorising route of a remittance's file (see {@link #download}). */
    public static String localPath(String club, String id) { return "/api/v1/remittances/files/" + club + "/" + id; }
}
