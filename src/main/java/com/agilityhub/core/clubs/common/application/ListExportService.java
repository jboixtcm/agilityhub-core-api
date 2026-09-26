package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.ExportJob;
import com.agilityhub.core.clubs.common.domain.ExportNames;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class ListExportService {
    private final ListEngine lists; private final ExportPolicy policy; private final ExportJobCoordinator jobs;
    private final ClubConfigService configs; private final Clock clock; private final ExportJobRenderer renderer;
    public ListExportService(ListEngine lists, ExportPolicy policy, ExportJobCoordinator jobs, ClubConfigService configs, Clock clock, ExportJobRenderer renderer) {
        this.lists = lists; this.policy = policy; this.jobs = jobs; this.configs = configs; this.clock = clock; this.renderer = renderer;
    }
    public record Result(String jobId, String fileName, byte[] file) { }
    public Result export(String key, String format, String selected, MultiValueMap<String, String> params) {
        policy.requireAllowed(format);
        var dataset = lists.dataset(key); var query = ListQuery.parse(dataset.definition(), params);
        var columns = dataset.definition().selectColumns(selected != null || query.fields().isEmpty() ? selected : fieldColumns(dataset.definition(), query.fields()));
        long rows = lists.exportCount(dataset, query, ExportPolicy.MAX_ROWS);
        if (rows > ExportPolicy.MAX_ROWS) { throw new ApiException(ErrorCode.EXPORT_TOO_LARGE); }
        String id = UUID.randomUUID().toString(); String clubId = TenantContext.require();
        var config = configs.get(clubId); Instant now = clock.instant();
        String name = ExportNames.fileName(config.club().slug(), key, now, ZoneId.of(config.club().timeZone()), format);
        boolean inline = rows <= ExportPolicy.SYNC_MAX_ROWS;
        var selection = new ListQuery(0, 1000, query.sort(), query.q(), query.filters(), List.of());
        var job = new ExportJob(id, clubId, ListAccess.account(), "LIST", key, format.toUpperCase(Locale.ROOT), inline ? "RUNNING" : "QUEUED", columns, selection,
                LocaleContext.current().toLanguageTag(), now, config.club().timeZone(), name, config.club().name(), config.primaryColor(), rows, 0,
                null, null, ExportPolicy.contentType(format), now.plus(Duration.ofDays(7)), inline ? 1 : 0, inline ? UUID.randomUUID().toString() : null,
                inline ? now.plusSeconds(300) : null, null, List.of(), null);
        jobs.submit(job);
        if (!inline) { return new Result(id, name, null); }
        try { return new Result(id, name, renderer.render(job, true)); }
        catch (RuntimeException ex) { jobs.fail(job, ex); throw ex; }
    }
    /**
     * CONVENCIONS_API §4 (amended 26-09, E5-T24): an export honours `fields`, already checked against the list's `x-fields` (any
     * other key is 400 INVALID_FILTER). Without `columns`, its keys that are export columns are the columns, in its order; keys
     * without a column (the row id) are left out, and `fields` that names no column is 400 INVALID_FILTER.
     */
    static String fieldColumns(ListDefinition definition, List<String> fields) {
        return String.join(",", fields.stream().filter(definition.columns()::contains).toList());
    }
}
