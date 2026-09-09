package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.shared.application.contract.ApiContracts.*;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class AuditReadService {
    private final AuditLists provider;
    private final ListEngine engine;
    public AuditReadService(AuditLists provider, ListEngine engine) { this.provider = provider; this.engine = engine; }
    public ListPage<Map<String, Object>> list(MultiValueMap<String, String> params, String member) {
        var data = provider.dataset(false, member);
        return engine.list(data, params);
    }
    public FilterValues facets(String field, MultiValueMap<String, String> params) {
        var data = provider.dataset(false, null); data.definition().field(field);
        return engine.facets(data, field, params);
    }
    public Map<String, Object> get(String id) {
        var data = provider.dataset(true, null);
        var stages = new ArrayList<Document>(); stages.add(new Document("$match", new Document("_id", id))); stages.addAll(data.stages());
        data = new ListDataset(data.definition(), data.collection(), stages, data.projection(), data.nullablePaths(), data.label(), data.sanitize());
        var rows = engine.exportRows(data, new ListQuery(0, 20, List.of("at,desc"), "", List.of(), List.of()), List.of());
        if (rows.isEmpty()) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return rows.getFirst();
    }
}
