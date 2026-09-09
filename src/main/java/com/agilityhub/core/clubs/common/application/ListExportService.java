package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.lists.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

@Service
public class ListExportService {
    private final ListEngine lists; private final ExportPolicy policy; private final ListExportRenderer renderer;
    private final ListExportRepository jobs; private final ClubConfigService configs; private final Clock clock; private final ExportCompletion completion;
    public ListExportService(ListEngine lists, ExportPolicy policy, ListExportRenderer renderer, ListExportRepository jobs, ClubConfigService configs, Clock clock, ExportCompletion completion) {
        this.lists = lists; this.policy = policy; this.renderer = renderer; this.jobs = jobs; this.configs = configs; this.clock = clock; this.completion = completion;
    }
    public record Result(String jobId, byte[] file) { }
    @Transactional
    @SuppressWarnings("unchecked")
    public Result export(String key, String format, String selected, MultiValueMap<String, String> params) {
        policy.requireAllowed(format);
        var dataset = lists.dataset(key); var query = ListQuery.parse(dataset.definition(), params);
        var columns = dataset.definition().selectColumns(selected);
        var rows = lists.exportRows(dataset, query, columns);
        String id = UUID.randomUUID().toString(); String clubId = TenantContext.require();
        if (rows.size() > 5000) {
            // Page and fields never restrict an export; preserve only its canonical selection.
            var selection = new ListQuery(0, 1000, query.sort(), query.q(), query.filters(), List.of());
            jobs.insert(new QueuedListExport(id, clubId, ListAccess.account(), "LIST", key, format.toUpperCase(Locale.ROOT), "QUEUED", columns, selection,
                    LocaleContext.current().toLanguageTag(), clock.instant()));
            return new Result(id, null);
        }
        var config = configs.get(clubId);
        var safe = rows.stream().map(row -> (Map<String, Object>) policy.mask(row)).toList();
        byte[] file = renderer.render(format, config.club().name(), config.primaryColor(), key, columns, safe);
        completion.completed(id, key, format, rows.size());
        return new Result(id, file);
    }
}
