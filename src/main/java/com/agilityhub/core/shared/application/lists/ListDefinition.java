package com.agilityhub.core.shared.application.lists;

import com.agilityhub.core.shared.application.contract.ApiContracts.FilterOperator;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.*;

/** Trusted endpoint allowlist. Paths always come from code, never from a request. */
public record ListDefinition(String key, Map<String, Field> filters, Map<String, String> sorts,
        List<String> searchable, List<String> columns, List<String> defaultColumns,
        List<String> defaultSort, Set<String> fields) {
    public enum Type { TEXT, NUMBER, BOOLEAN, DATE, INSTANT }
    public record Field(String path, Type type, Set<FilterOperator> operators) {
        public Field(String path, Type type) { this(path, type, allowed(type)); }
        private static Set<FilterOperator> allowed(Type type) {
            var ops = EnumSet.of(FilterOperator.eq, FilterOperator.ne, FilterOperator.in,
                    FilterOperator.nin, FilterOperator.exists);
            if (type == Type.NUMBER || type == Type.DATE || type == Type.INSTANT) {
                ops.addAll(EnumSet.of(FilterOperator.lt, FilterOperator.lte, FilterOperator.gt,
                        FilterOperator.gte, FilterOperator.between));
            }
            if (type == Type.TEXT) { ops.addAll(EnumSet.of(FilterOperator.contains, FilterOperator.startsWith)); }
            return Collections.unmodifiableSet(ops);
        }
    }
    public Field field(String name) {
        var field = filters.get(name);
        if (field == null) { throw invalid(); }
        return field;
    }
    public List<String> selectColumns(String input) {
        var selected = input == null ? defaultColumns : ListQuery.csv(input);
        if (selected.isEmpty() || !columns.containsAll(selected)) { throw invalid(); }
        return selected;
    }
    public static ApiException invalid() { return new ApiException(ErrorCode.INVALID_FILTER); }
}
