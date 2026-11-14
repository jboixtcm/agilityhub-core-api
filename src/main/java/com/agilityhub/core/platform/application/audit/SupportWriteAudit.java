package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import com.agilityhub.core.shared.application.SupportWriteAuditor;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;

/**
 * S17 R-17-10 (AUTHZ-05, owner decision 13-11-2026): every write made with a support access leaves an entry with
 * `origin = PLATFORM_SUPPORT` in the club's audit. A write whose service audits it already (`@Audited` or an explicit
 * {@link AuditWriter} call, which a support actor writes with that origin) keeps its own entry alone; any other one gets
 * {@link AuditAction#SUPPORT_WRITE} on the support access's grant, naming the request. Called after the handler answered, so the
 * entries of the request's own transactions are committed by then.
 */
@Service
public class SupportWriteAudit implements SupportWriteAuditor {
    private final AuditRepository entries;
    private final AuditWriter writer;
    private final AuditActorProvider actors;
    private final Clock clock;

    public SupportWriteAudit(AuditRepository entries, AuditWriter writer, AuditActorProvider actors, Clock clock) {
        this.entries = entries; this.writer = writer; this.actors = actors; this.clock = clock;
    }

    @Override public Instant started() { return clock.instant(); }

    /**
     * Unless the request left an entry of its own in the club's audit (its trace, `origin = PLATFORM_SUPPORT`, since
     * {@code since}), writes `SUPPORT_WRITE {method, route, path, status}` on the grant.
     */
    @Override
    public boolean record(String grantId, Instant since, String method, String route, String path, int status) {
        if (entries.supportEntrySince(actors.current().traceId(), since)) { return false; }
        var details = new LinkedHashMap<String, Object>();
        details.put("method", method);
        if (route != null) { details.put("route", route); }
        details.put("path", path);
        details.put("status", status);
        writer.write(new AuditCommand(AuditAction.SUPPORT_WRITE, "ImpersonationGrant", grantId, null, null, null, null), details);
        return true;
    }
}
