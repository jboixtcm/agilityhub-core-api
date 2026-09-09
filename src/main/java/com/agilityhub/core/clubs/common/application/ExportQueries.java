package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.lists.ListAccess;
import com.agilityhub.core.shared.domain.*;
import java.io.InputStream;
import java.time.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class ExportQueries {
    public record View(String id, String kind, String listKey, String format, String status, Long rows, Integer progressPct,
            String fileName, String downloadUrl, Instant createdAt, Instant expiresAt, String errorCode, String errorMessage) { }
    public record Download(InputStream stream, String fileName, String contentType, long size) { }
    private final ListExportRepository jobs; private final ExportStorage storage; private final Clock clock; private final IcuMessageSource messages;
    public ExportQueries(ListExportRepository jobs, ExportStorage storage, Clock clock, IcuMessageSource messages) {
        this.jobs = jobs; this.storage = storage; this.clock = clock; this.messages = messages;
    }
    private String account() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp")) || auth.getAuthorities().stream().noneMatch(role -> Set.of("ROLE_ADMIN", "ROLE_MEMBER").contains(role.getAuthority()))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        TenantContext.require(); return jwt.getSubject();
    }
    public List<View> list(String kind) {
        String account = account();
        if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        return jobs.owned(account, kind).stream().map(job -> view(job, false)).toList();
    }
    public View get(String id) { return view(owned(id), true); }
    private ExportJob owned(String id) {
        String account = account();
        return jobs.findById(id).filter(job -> account.equals(job.ownerAccountId())).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    private boolean expired(ExportJob job) { return job.status().equals("EXPIRED") || job.expiresAt() != null && !clock.instant().isBefore(job.expiresAt()); }
    private View view(ExportJob job, boolean detail) {
        boolean expired = expired(job);
        if (detail && expired) { throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
        String url = detail && job.status().equals("READY") ? storage.downloadUrl(job.id(), job.clubId(), job.ownerAccountId(), job.fileKey(), job.fileName(), job.expiresAt()) : null;
        String error = job.errorCode() == null ? null : messages.getMessage("error." + job.errorCode(), null, LocaleContext.current());
        return new View(job.id(), job.kind(), job.listKey(), job.format(), expired ? "EXPIRED" : job.status(), job.rows(), job.progressPct(), job.fileName(), url,
                job.createdAt(), job.expiresAt(), job.errorCode(), error);
    }
    public Download download(String id, long expires, String signature) {
        var job = owned(id);
        if (expired(job)) { throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
        if (!job.status().equals("READY")) { throw new ApiException(ErrorCode.INVALID_STATE); }
        if (!(storage instanceof LocalExportStorage local)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        local.verify(job.id(), job.clubId(), job.ownerAccountId(), job.expiresAt(), expires, signature);
        return new Download(local.open(job.fileKey()), job.fileName(), job.contentType(), job.sizeBytes());
    }
}
