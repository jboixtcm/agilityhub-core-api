package com.agilityhub.core.shared.application.lists;

import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/**
 * CONVENCIONS_API §4 (E5-T20, E5-T22): with `fields`, a list item keeps its row id and the requested keys only. A key that was
 * not requested is left out, never `null` in its place; a requested one keeps its `null`. For the lists that build whole items
 * after the engine's page (typed records, reloaded aggregates). The engine has already validated `fields` against the list's
 * allowlist, its `x-fields`.
 */
public final class SparseItems {
    private SparseItems() { }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> ListPage<T> apply(ObjectMapper mapper, ListPage<T> page, MultiValueMap<String, String> params, String rowId) {
        var requested = params.get("fields");
        if (requested == null || requested.isEmpty()) { return page; }
        Set<String> fields = Set.copyOf(ListQuery.csv(requested.getFirst()));
        var items = page.items().stream().map(item -> {
            Map<String, Object> row = mapper.convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() { });
            row.keySet().removeIf(key -> !key.equals(rowId) && !fields.contains(key));
            return row;
        }).toList();
        return (ListPage) new ListPage<>(items, page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters());
    }
}
