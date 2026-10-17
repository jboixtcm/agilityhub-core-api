package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.persistence.privacy.ErasureRequest;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * S14 §6 / S17 `GET /platform/erasure-requests`: the console's universal list, filterable by `clubId` — the clubs a request covers
 * (`clubIds`); `clubId:exists:false` = the platform's account requests (no club of their own).
 */
@Service
public class ErasureQueries {
    static final ListDefinition DEFINITION = new ListDefinition("platformErasureRequests",
            Map.of("clubId", new ListDefinition.Field("clubIds", ListDefinition.Type.TEXT)), Map.of(), List.of(), List.of(), List.of(), List.of(), Set.of());
    private final ErasureRequestRepository requests;
    public ErasureQueries(ErasureRequestRepository requests) { this.requests = requests; }

    public ListPage<ErasureRequest> platform(MultiValueMap<String, String> params) {
        var query = ListQuery.parse(DEFINITION, params);
        var criteria = new ArrayList<Criteria>();
        for (Filter filter : query.filters()) { criteria.add(criterion(filter)); }
        long count = requests.count(criteria);
        return new ListPage<>(requests.page(criteria, query.page(), query.size()), query.page(), query.size(), count,
                (int) ((count + query.size() - 1) / query.size()), query.filters());
    }
    static Criteria criterion(Filter filter) {
        var clubs = Criteria.where("clubIds");
        Object value = filter.value();
        return switch (filter.op()) {
            case exists -> Criteria.where("clubId").exists((Boolean) value);
            case ne -> clubs.ne(value);
            case in -> clubs.in((List<?>) value);
            case nin -> clubs.nin((List<?>) value);
            default -> clubs.is(value);
        };
    }
}
