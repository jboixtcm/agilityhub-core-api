package com.agilityhub.core.shared.application.lists;

import com.agilityhub.core.shared.application.contract.ApiContracts.*;
import com.agilityhub.core.shared.persistence.MongoListRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class ListEngine {
    private final Map<String, ListProvider> providers = new HashMap<>();
    private final MongoListRepository repository;
    public ListEngine(List<ListProvider> providers, MongoListRepository repository) {
        this.repository = repository;
        providers.forEach(provider -> provider.keys().forEach(key -> {
            if (this.providers.putIfAbsent(key, provider) != null) { throw new IllegalArgumentException("Duplicate list " + key); }
        }));
    }
    public ListDataset dataset(String key) {
        var provider = providers.get(key);
        if (provider == null) { throw ListDefinition.invalid(); }
        return provider.dataset(key);
    }
    public ListPage<Map<String, Object>> list(String key, MultiValueMap<String, String> params) {
        var dataset = dataset(key);
        var query = ListQuery.parse(dataset.definition(), params);
        return repository.page(dataset, query);
    }
    public FilterValues facets(String key, String field, MultiValueMap<String, String> params) {
        var dataset = dataset(key);
        dataset.definition().field(field);
        var query = ListQuery.parse(dataset.definition(), params).withoutField(field);
        return new FilterValues(field, repository.facets(dataset, query, field));
    }
    /** Bounded probe: 5,001 proves the synchronous limit without loading the whole list. */
    public List<Map<String, Object>> exportRows(ListDataset dataset, ListQuery query, List<String> columns) {
        return repository.rows(dataset, query, 0, 5001, columns);
    }
    public long exportCount(ListDataset dataset, ListQuery query) { return repository.exportCount(dataset, query, 100001); }
    public java.util.stream.Stream<Map<String, Object>> exportStream(ListDataset dataset, ListQuery query, List<String> columns) {
        return repository.exportStream(dataset, query, columns, 100001);
    }
}
