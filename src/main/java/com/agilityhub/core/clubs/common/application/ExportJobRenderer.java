package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.clubs.common.domain.ExportNames;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class ExportJobRenderer {
    private final ListEngine lists; private final ListExportRenderer renderer; private final ExportPolicy policy;
    private final ExportStorage storage; private final ExportJobCoordinator coordinator; private final ListExportRepository jobs;
    private final ClubConfigService configs; private final Clock clock;
    public ExportJobRenderer(ListEngine lists, ListExportRenderer renderer, ExportPolicy policy, ExportStorage storage,
            ExportJobCoordinator coordinator, ListExportRepository jobs, ClubConfigService configs, Clock clock) {
        this.lists = lists; this.renderer = renderer; this.policy = policy; this.storage = storage;
        this.coordinator = coordinator; this.jobs = jobs; this.configs = configs; this.clock = clock;
    }
    @SuppressWarnings("unchecked")
    public byte[] render(ExportJob original, boolean inline) {
        try (var scope = new ExportExecutionScope(original)) {
            ExportJob job = hydrateLegacy(original);
            var dataset = lists.dataset(job.listKey());
            dataset.definition().selectColumns(String.join(",", job.columns()));
            ListQuery.validateSort(dataset.definition(), job.query().sort());
            job.query().filters().forEach(filter -> ListQuery.validateFilter(dataset.definition(), filter));
            String key = "exports/" + job.clubId() + "/" + job.id() + "/" + job.claimToken() + "/" + job.fileName();
            coordinator.heartbeat(job, 0); jobs.registerFile(job, clock.instant(), key);
            Path file = Files.createTempFile("agilityhub-export-", "." + job.format().toLowerCase(Locale.ROOT));
            try {
                long[] count = {0};
                try (var stream = lists.exportStream(dataset, job.query(), job.columns()); var output = Files.newOutputStream(file)) {
                    var safe = stream.map(row -> {
                        if (++count[0] > ExportPolicy.MAX_ROWS) { throw new ApiException(ErrorCode.EXPORT_TOO_LARGE); }
                        if (count[0] % 200 == 0) { coordinator.heartbeat(job, job.rows() == null || job.rows() == 0 ? 0 : (int) (count[0] * 100 / job.rows())); }
                        return (Map<String, Object>) policy.mask(row);
                    });
                    renderer.renderTo(job.format().toLowerCase(Locale.ROOT), job.clubName(), job.primaryColor(), job.listKey(), job.columns(), safe::iterator,
                            Locale.forLanguageTag(job.locale()), ZoneId.of(job.timeZone()), output);
                }
                coordinator.heartbeat(job, 99);
                storage.put(key, file, job.contentType());
                coordinator.complete(job, key, Files.size(file), count[0]);
                return inline ? Files.readAllBytes(file) : null;
            } finally { Files.deleteIfExists(file); }
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    /** Old handoffs did not freeze presentation metadata; freeze it once, before their first render. */
    private ExportJob hydrateLegacy(ExportJob job) {
        if (job.fileName() != null) { return job; }
        var config = configs.get(job.clubId());
        String name = ExportNames.fileName(config.club().slug(), job.listKey(), job.createdAt(), ZoneId.of(config.club().timeZone()), job.format());
        jobs.updateClaim(job, clock.instant(), new Update().set("timeZone", config.club().timeZone()).set("fileName", name)
                .set("clubName", config.club().name()).set("primaryColor", config.primaryColor())
                .set("contentType", ExportPolicy.contentType(job.format())).set("expiresAt", clock.instant().plus(Duration.ofDays(7))));
        return jobs.findById(job.id()).orElseThrow();
    }
}
