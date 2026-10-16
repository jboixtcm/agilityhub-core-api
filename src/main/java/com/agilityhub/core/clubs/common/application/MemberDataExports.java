package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.domain.ExportNames;
import com.agilityhub.core.clubs.common.persistence.ExportJob;
import com.agilityhub.core.clubs.common.persistence.ListExportRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.privacy.ErasureAccounts;
import com.agilityhub.core.platform.application.privacy.ErasureDirectory;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import com.agilityhub.core.platform.application.privacy.PrivacyFileStore;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * S14 R-14-13, the right of access: `POST /members/{id}/data-export` (ADMIN, `deliverToMember`) and `POST /me/data-export` (the member,
 * always delivered) queue an `ExportJob{kind: MEMBER_DATA, format: ZIP}` — always asynchronous, one per member every 24 hours
 * (`DATA_EXPORT_TOO_SOON`). The export worker renders it: `data.json` (`schema: memberData.v1`, every context's sections),
 * `resum.pdf` (the same sections, branded, in the member's locale) and `fitxers/` (the dogs' photos and documents, the
 * attachments); 7 days in the export store behind a signed URL, then `MEMBER_DATA_EXPORTED` and N-50.
 */
@Service
public class MemberDataExports {
    public static final String LIST_KEY = "member-data";
    private final ErasureDirectory directory;
    private final ErasureAccounts accounts;
    private final List<MemberDataSource> sources;
    private final Map<String, PrivacyFileStore> stores;
    private final ExportJobCoordinator coordinator;
    private final ListExportRepository jobs;
    private final ExportStorage storage;
    private final ListExportRenderer renderer;
    private final ClubConfigService configs;
    private final IcuMessageSource messages;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MemberDataExports(ErasureDirectory directory, ErasureAccounts accounts, List<MemberDataSource> sources, List<PrivacyFileStore> stores,
            ExportJobCoordinator coordinator, ListExportRepository jobs, ExportStorage storage, ListExportRenderer renderer, ClubConfigService configs,
            IcuMessageSource messages, ObjectMapper mapper, Clock clock) {
        this.directory = directory; this.accounts = accounts; this.sources = List.copyOf(sources);
        this.stores = stores.stream().collect(Collectors.toMap(PrivacyFileStore::store, Function.identity()));
        this.coordinator = coordinator; this.jobs = jobs; this.storage = storage; this.renderer = renderer; this.configs = configs;
        this.messages = messages; this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT); this.clock = clock;
    }

    /**
     * `POST /members/{id}/data-export`: another club's member → `404`; an erased member → `409 MEMBER_ERASED` (INC-53 point 10:
     * nothing personal is left to give, and no address to deliver it to).
     */
    public String requestFor(String memberId, boolean deliverToMember) {
        var member = directory.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (member.erased()) { throw new ApiException(ErrorCode.MEMBER_ERASED); }
        return submit(member, account(), deliverToMember);
    }
    /** `POST /me/data-export`: the caller's own member of the club (the controller refuses an impersonation token). */
    public String requestMine() {
        String account = account();
        var member = directory.ofAccount(account).stream().filter(candidate -> !candidate.erased()).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return submit(member, account, true);
    }
    private static String account() {
        var user = CurrentUser.current();
        if (user == null || user.accountId() == null) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        return user.accountId();
    }
    private String submit(ErasureMember member, String owner, boolean deliver) {
        String clubId = TenantContext.require();
        var config = configs.get(clubId);
        Instant now = clock.instant();
        String locale = member.accountId() == null ? null : accounts.account(member.accountId()).map(ErasureAccounts.Account::locale).orElse(null);
        if (locale == null || !config.club().locales().contains(locale)) { locale = config.club().defaultLocale(); }
        String id = UUID.randomUUID().toString();
        String name = ExportNames.fileName(config.club().slug(), LIST_KEY, now, ZoneId.of(config.club().timeZone()), "zip");
        var job = new ExportJob(id, clubId, owner, "MEMBER_DATA", LIST_KEY, "ZIP", "QUEUED", List.of(), null, locale, now, config.club().timeZone(),
                name, config.club().name(), config.primaryColor(), 0L, 0, null, null, "application/zip", now.plus(ExportPolicy.MEMBER_DATA_RETENTION),
                0, null, null, null, List.of(), null, member.id(), deliver);
        coordinator.submitMemberData(job, now.minus(ExportPolicy.MEMBER_DATA_INTERVAL));
        return id;
    }

    /** The worker's rendering of a claimed `MEMBER_DATA` job, inside the job's own scope (tenant, locale, the requester's identity). */
    public void render(ExportJob job) {
        try (var scope = new ExportExecutionScope(job)) {
            var member = directory.member(job.subjectMemberId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            var data = new MemberDataPackage();
            sources.forEach(source -> source.contribute(member, data));
            String key = "exports/" + job.clubId() + "/" + job.id() + "/" + job.claimToken() + "/" + job.fileName();
            coordinator.heartbeat(job, 0); jobs.registerFile(job, clock.instant(), key);
            Path file = Files.createTempFile("agilityhub-member-data-", ".zip");
            try {
                long files = write(job, data, file);
                coordinator.heartbeat(job, 99);
                storage.put(key, file, "application/zip");
                coordinator.completeMemberData(job, key, Files.size(file), files);
            } finally { Files.deleteIfExists(file); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    /** The ZIP: `data.json`, `resum.pdf` and `fitxers/…`; returns the number of files packed under `fitxers/`. */
    long write(ExportJob job, MemberDataPackage data, Path file) throws IOException {
        var document = new LinkedHashMap<String, Object>();
        document.put("schema", MemberDataPackage.SCHEMA); document.put("generatedAt", clock.instant().toString());
        document.put("club", Map.of("id", job.clubId(), "name", job.clubName() == null ? "" : job.clubName()));
        document.putAll(data.sections());
        Locale locale = Locale.forLanguageTag(job.locale());
        long packed = 0;
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("data.json")); zip.write(mapper.writeValueAsBytes(document)); zip.closeEntry();
            var pdf = new java.io.ByteArrayOutputStream();
            renderer.summary(job.clubName(), job.primaryColor(), messages.getMessage("dataExport.title", null, locale), summary(data, locale), locale, pdf);
            zip.putNextEntry(new ZipEntry("resum.pdf")); zip.write(pdf.toByteArray()); zip.closeEntry();
            var paths = new LinkedHashSet<String>();
            for (var item : data.files()) {
                var store = stores.get(item.store());
                if (store == null) { continue; }
                String path = "fitxers/" + safe(item.path());
                if (!paths.add(path)) { continue; }
                zip.putNextEntry(new ZipEntry(path));
                try (var input = store.read(item.key())) { input.transferTo(zip); }
                zip.closeEntry();
                packed++;
            }
        }
        return packed;
    }

    /** `resum.pdf`: each section with its `dataExport.section.*` label, one line per row. */
    Map<String, List<String>> summary(MemberDataPackage data, Locale locale) {
        var sections = new LinkedHashMap<String, List<String>>();
        data.sections().forEach((name, value) -> {
            var rows = new ArrayList<String>();
            if (value instanceof List<?> list) { list.forEach(item -> rows.add(ListExportRenderer.summaryRow(item))); }
            else if (value != null) { rows.add(ListExportRenderer.summaryRow(value)); }
            if (rows.isEmpty()) { rows.add("—"); }
            sections.put(messages.getMessage("dataExport.section." + name, null, locale), rows);
        });
        return sections;
    }
    /** A ZIP entry path from data: each segment without separators or parent references. */
    static String safe(String path) {
        var segments = new ArrayList<String>();
        for (String segment : path.split("/")) {
            String clean = segment.replaceAll("[\\\\:*?\"<>|\\p{Cntrl}]", "_").strip();
            if (!clean.isEmpty() && !clean.equals(".") && !clean.equals("..")) { segments.add(clean); }
        }
        return String.join("/", segments);
    }
}
