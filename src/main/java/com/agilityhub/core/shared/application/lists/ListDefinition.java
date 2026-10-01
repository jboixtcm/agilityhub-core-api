package com.agilityhub.core.shared.application.lists;

import com.agilityhub.core.shared.application.contract.ApiContracts.FilterOperator;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.*;

/**
 * Trusted endpoint allowlist. Paths always come from code, never from a request. `maxSize` is the largest page size of
 * {@link ListQuery#SIZES} the list accepts (1000 unless a resource pages smaller, as D14's 50 rows of S10 §3); a larger
 * one is `400 INVALID_FILTER`, like any size outside the four, and the operation publishes the same limit in its `size`
 * enum (`@ListContract(maxSize)`).
 */
public record ListDefinition(String key, Map<String, Field> filters, Map<String, String> sorts,
        List<String> searchable, List<String> columns, List<String> defaultColumns,
        List<String> defaultSort, Set<String> fields, int maxSize) {
    public ListDefinition {
        if (!ListQuery.SIZES.contains(maxSize)) { throw new IllegalArgumentException("maxSize must be one of " + ListQuery.SIZES + ": " + maxSize); }
    }
    public ListDefinition(String key, Map<String, Field> filters, Map<String, String> sorts, List<String> searchable, List<String> columns,
            List<String> defaultColumns, List<String> defaultSort, Set<String> fields) {
        this(key, filters, sorts, searchable, columns, defaultColumns, defaultSort, fields, ListQuery.SIZES.getLast());
    }
    /** The same allowlist with pages of at most {@code maxSize} rows. */
    public ListDefinition withMaxSize(int maxSize) {
        return new ListDefinition(key, filters, sorts, searchable, columns, defaultColumns, defaultSort, fields, maxSize);
    }
    public enum Type { TEXT, NUMBER, BOOLEAN, DATE, INSTANT }
    /** The path of a field the contract publishes before its owner computes it (E8-T01): using it answers 501 NOT_IMPLEMENTED. */
    static final String DEFERRED = "#deferred";
    public record Field(String path, Type type, Set<FilterOperator> operators) {
        public Field(String path, Type type) { this(path, type, allowed(type)); }
        /**
         * A field of the published contract whose value a later task computes (e.g. S13 R-13-17's member filters, E8-T05): the
         * list accepts the key, so `x-filterable` and the allowlist stay equal, and a filter or a facet on it is
         * `501 NOT_IMPLEMENTED` instead of an empty answer.
         */
        public static Field deferred(Type type) { return new Field(DEFERRED, type); }
        public boolean isDeferred() { return DEFERRED.equals(path); }
        /** `501 NOT_IMPLEMENTED` for a deferred field. */
        public Field requireImplemented() {
            if (isDeferred()) { throw new ApiException(ErrorCode.NOT_IMPLEMENTED); }
            return this;
        }
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
