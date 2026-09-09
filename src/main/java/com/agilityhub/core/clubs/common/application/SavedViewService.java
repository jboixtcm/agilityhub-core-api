package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedViewService {
    private final SavedViewRepository repository;
    private final ListEngine lists;
    private final Clock clock;
    public SavedViewService(SavedViewRepository repository, ListEngine lists, Clock clock) { this.repository = repository; this.lists = lists; this.clock = clock; }
    public List<SavedViewData> list(String key) {
        String account = ListAccess.account(); lists.dataset(key);
        return repository.visible(key, account).stream().map(this::publicView).toList();
    }
    public SavedViewData get(String id) { return publicView(read(id)); }
    private SavedView read(String id) {
        String account = ListAccess.account();
        var view = repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!view.data().ownerAccountId().equals(account) && !view.data().shared() && !ListAccess.admin()) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return view;
    }
    private void writable(SavedView view) {
        if (!view.data().ownerAccountId().equals(ListAccess.account()) && !ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
    }
    @Transactional
    public SavedViewData create(String key, String name, List<String> columns, List<Filter> filters, List<String> sort, boolean shared) {
        String id = UUID.randomUUID().toString();
        var data = validated(id, ListAccess.account(), key, name, columns, filters, sort, shared, 0);
        try { return publicView(repository.insert(new SavedView(id, TenantContext.require(), data, clock.instant(), clock.instant()))); }
        catch (DuplicateKeyException ex) { throw new ApiException(ErrorCode.SAVED_VIEW_NAME_TAKEN); }
    }
    @Transactional
    public SavedViewData update(String id, String key, String name, List<String> columns, List<Filter> filters, List<String> sort, boolean shared, long version) {
        var old = read(id); writable(old);
        if (version != old.data().version()) { throw new ApiException(ErrorCode.STALE_VERSION); }
        var data = validated(id, old.data().ownerAccountId(), key, name, columns, filters, sort, shared, version + 1);
        try { return publicView(repository.update(new SavedView(id, old.clubId(), data, old.createdAt(), clock.instant()), version)); }
        catch (DuplicateKeyException ex) { throw new ApiException(ErrorCode.SAVED_VIEW_NAME_TAKEN); }
    }
    @Transactional public void delete(String id) { var view = read(id); writable(view); repository.delete(view); }
    private SavedViewData validated(String id, String owner, String key, String name, List<String> columns, List<Filter> filters, List<String> sort, boolean shared, long version) {
        var definition = lists.dataset(key).definition();
        if (name == null || name.isBlank() || name.strip().length() > 40 || columns == null || columns.isEmpty()
                || columns.stream().anyMatch(Objects::isNull) || filters == null) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        if (!definition.columns().containsAll(columns) || new HashSet<>(columns).size() != columns.size()) { throw ListDefinition.invalid(); }
        return new SavedViewData(id, owner, key, name.strip(), List.copyOf(columns), filters.stream()
                .map(f -> ListQuery.validateFilter(definition, f)).toList(), ListQuery.validateSort(definition, sort), shared, version);
    }
    private SavedViewData publicView(SavedView view) {
        var data = view.data();
        var definition = lists.dataset(data.listKey()).definition();
        // Old column keys and columns restricted by the reader's role are omitted on load.
        return new SavedViewData(data.id(), data.ownerAccountId(), data.listKey(), data.name(), data.columns().stream().filter(definition.columns()::contains).toList(),
                data.filters().stream().filter(f -> definition.filters().containsKey(f.field())).toList(),
                data.sort().stream().filter(s -> definition.sorts().containsKey(s.split(",")[0])).toList(), data.shared(), data.version());
    }
}
