package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.clubs.census.application.CompetitionDogDirectory;
import com.agilityhub.core.clubs.statistics.persistence.*;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionValidation.*;

@Service
public class CompetitionImports {
    private final FcagStandingsParser fcag; private final FlowResultsParser flow; private final FcagSourceClient client;
    private final FlowSourceClient flowClient; private final FlowPrintResultsParser flowHtml;
    private final CompetitionDogDirectory dogs; private final CompetitionImportRepository imports;
    private final CompetitionPreviewRepository previews; private final Clock clock;
    public CompetitionImports(FcagStandingsParser fcag, FlowResultsParser flow, FcagSourceClient client, CompetitionDogDirectory dogs,
            CompetitionImportRepository imports, CompetitionPreviewRepository previews, Clock clock, FlowSourceClient flowClient, FlowPrintResultsParser flowHtml) {
        this.flowClient = flowClient; this.flowHtml = flowHtml;
        this.fcag = fcag; this.flow = flow; this.client = client; this.dogs = dogs; this.imports = imports; this.previews = previews; this.clock = clock;
    }
    public Preview preview(ImportRequest request, String replayKey) {
        String sourceUrl = url(request.source(), request.sourceUrl());
        var parsed = request.source() == Source.FCAG
                ? fcag.parse(request.content() == null || request.content().isBlank() ? client.fetch(sourceUrl) : request.content(), sourceUrl)
                : parseFlow(request, sourceUrl);
        String sourceId = hash(TenantContext.require() + "|" + parsed.source() + "|" + parsed.sourceUrl() + "|" + parsed.season());
        var old = imports.findById(sourceId).orElse(null);
        var available = dogs.list(parsed.source() == Source.FCAG ? "FCAG" : "RSCE");
        var byLicense = new HashMap<String, List<String>>();
        available.forEach(dog -> byLicense.computeIfAbsent(dog.license().trim(), ignored -> new ArrayList<>()).add(dog.id()));
        var rows = parsed.entries().stream().map(entry -> {
            var matches = byLicense.getOrDefault(entry.license(), List.of());
            return new PreviewRow(entry, matches.size() == 1 ? matches.getFirst() : null,
                    matches.isEmpty() ? "UNMATCHED" : matches.size() == 1 ? "EXACT_LICENSE" : "AMBIGUOUS_LICENSE");
        }).toList();
        String id = UUID.randomUUID().toString(); var expiry = clock.instant().plus(Duration.ofHours(24));
        previews.insert(new CompetitionPreviewRecord(id, TenantContext.require(), parsed, sourceId, old == null ? 0 : old.revision(), expiry, null, null, com.agilityhub.core.shared.application.CurrentUser.current().accountId(), replayKey));
        return new Preview(id, parsed.source(), parsed.sourceUrl(), parsed.season(), expiry, rows,
                available.stream().map(dog -> new DogOption(dog.id(), dog.name(), dog.license())).toList());
    }
    private Parsed parseFlow(ImportRequest request, String sourceUrl) {
        String body = request.content() == null || request.content().isBlank() ? flowClient.fetch(sourceUrl) : request.content();
        if (body.startsWith("\uFEFF")) { body = body.substring(1); }
        return body.stripLeading().startsWith("<") ? flowHtml.parse(body, sourceUrl) : flow.parse(body, sourceUrl);
    }
    public List<Receipt> history() {
        return imports.findAll().stream().sorted(Comparator.comparing(CompetitionImportRecord::importedAt).reversed()).map(CompetitionImports::receipt).toList();
    }
    @Transactional
    @Audited(action = AuditAction.MIGRATION_APPLIED, entityType = "'CompetitionImport'", entity = "#result.id")
    public Receipt confirm(String previewId, Confirmation request) {
        var preview = previews.findById(previewId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!clock.instant().isBefore(preview.expiresAt())) { throw invalid("PREVIEW_EXPIRED"); }
        var assignments = request.assignments();
        if (assignments == null || assignments.isEmpty() || assignments.size() > MAX_ROWS
                || assignments.entrySet().stream().anyMatch(row -> row.getKey() == null || row.getValue() == null || row.getValue().isBlank())) {
            throw invalid("NO_ASSIGNMENTS");
        }
        String mappingHash = hash(new TreeMap<>(assignments).entrySet().stream()
                .map(row -> row.getKey().length() + ":" + row.getKey() + row.getValue().length() + ":" + row.getValue())
                .collect(java.util.stream.Collectors.joining()));
        if (preview.receipt() != null) {
            if (!mappingHash.equals(preview.assignmentsHash())) { throw new ApiException(ErrorCode.STALE_VERSION); }
            return preview.receipt();
        }
        var source = preview.parsed();
        var available = new HashSet<>(dogs.list(source.source() == Source.FCAG ? "FCAG" : "RSCE").stream().map(CompetitionDogDirectory.LicensedDog::id).toList());
        if (!available.containsAll(assignments.values())) { throw invalid("DOG_NOT_LICENSED"); }
        new HashSet<>(assignments.values()).forEach(dogs::requireMutable);
        var keys = source.entries().stream().map(Entry::key).collect(java.util.stream.Collectors.toSet());
        if (!keys.containsAll(assignments.keySet())) { throw invalid("UNKNOWN_RESULT"); }
        var entries = source.entries().stream().filter(row -> assignments.containsKey(row.key()))
                .map(row -> new SavedEntry(assignments.get(row.key()), row)).toList();
        // One dog cannot occupy two standings in a category or two positions in one run.
        var identities = new HashSet<String>();
        for (var entry : entries) {
            String identity = entry.dogId() + "|" + (source.source() == Source.FCAG ? entry.result().division() + "|" + entry.result().category()
                    : entry.result().trialId() + "|" + entry.result().runId());
            if (!identities.add(identity)) { throw invalid("DUPLICATE_DOG_RESULT"); }
        }
        var saved = imports.save(new CompetitionImportRecord(preview.sourceId(), TenantContext.require(), source.source(), source.sourceUrl(), source.season(),
                clock.instant(), preview.sourceRevision() + 1, entries), preview.sourceRevision());
        var receipt = receipt(saved);
        previews.replace(new CompetitionPreviewRecord(preview.id(), preview.clubId(), preview.parsed(), preview.sourceId(), preview.sourceRevision(),
                preview.expiresAt(), receipt, mappingHash, preview.replayAccountId(), preview.replayKey()));
        return receipt;
    }
    static Receipt receipt(CompetitionImportRecord record) {
        return new Receipt(record.id(), record.source(), record.sourceUrl(), record.season(), record.importedAt(), record.entries().size(), record.revision());
    }
}
