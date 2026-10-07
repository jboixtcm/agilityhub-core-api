package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;

@Service
public class AccountingExportAdapter implements AccountingExportPort {
    private final ListExportService exports; private final ClubConfigService configs;
    public AccountingExportAdapter(ListExportService exports, ClubConfigService configs) { this.exports = exports; this.configs = configs; }
    @Override public Result export(String period, String format) {
        String selected = format == null ? configs.get(TenantContext.require()).get("billing.accountingExportFormat", String.class).toLowerCase(Locale.ROOT) : format;
        var params = new LinkedMultiValueMap<String, String>(); params.add("filter", "period:eq:" + period);
        var result = exports.export("accounting", selected, null, params);
        return new Result(result.jobId(), result.fileName(), ExportPolicy.contentType(selected), result.file());
    }
}
