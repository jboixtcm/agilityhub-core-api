package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.*;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MigrationPhotos {
    /** Only the target id and fixed codes leave this service; names and source URLs never do. */
    public record Row(String dogId, String outcome) { }
    private final CensusMigrationService census; private final DocumentService documents; private final AttachmentService attachments;
    private final ClubConfigService configs; private final MigrationPhotoFetcher fetcher;
    public MigrationPhotos(CensusMigrationService census, DocumentService documents, AttachmentService attachments, ClubConfigService configs, MigrationPhotoFetcher fetcher) {
        this.census = census; this.documents = documents; this.attachments = attachments; this.configs = configs; this.fetcher = fetcher;
    }
    public List<Row> run(String clubId, boolean dryRun) {
        try (var tenant = TenantContext.open(clubId)) {
            var result = new ArrayList<Row>(); int max = Math.multiplyExact(configs.get(clubId).get("files.dogPhotoMaxMb", Integer.class), 1024 * 1024);
            for (var dog : census.snapshot("dogs")) {
                String id = dog.get("_id").toString(); var sources = CensusValues.map(dog.get("sourceIds"));
                String source = Objects.toString(sources.get("playoffPhoto"), "");
                if (source.isBlank() || source.equals("[redacted]") || !Objects.toString(dog.get("photoFileKey"), "").isBlank()) { result.add(new Row(id, "SKIPPED")); continue; }
                if (dryRun) { result.add(new Row(id, "WOULD_IMPORT")); continue; }
                AttachmentService.File file = null; boolean bound = false;
                try {
                    var photo = fetcher.fetch(source, max); file = attachments.storeMigrationPhoto(photo.type(), photo.bytes());
                    bound = documents.migrationPhoto(id, file); result.add(new Row(id, bound ? "IMPORTED" : "SKIPPED"));
                } catch (ApiException failure) { result.add(new Row(id, failure.code().name())); }
                catch (RuntimeException failure) { result.add(new Row(id, "INTERNAL_ERROR")); }
                finally { if (file != null && !bound) { attachments.discardMigrationPhoto(file); } }
            }
            return List.copyOf(result);
        }
    }
}
