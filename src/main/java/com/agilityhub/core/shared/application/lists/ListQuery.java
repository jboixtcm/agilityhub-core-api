package com.agilityhub.core.shared.application.lists;

import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterOperator;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.util.MultiValueMap;
import static com.agilityhub.core.shared.application.lists.ListDefinition.invalid;

/** Canonical, typed query shared by lists, facets, saved views and exports. */
public record ListQuery(int page, int size, List<String> sort, String q, List<Filter> filters, List<String> fields) {
    public static ListQuery parse(ListDefinition definition, MultiValueMap<String, String> params) {
        try {
            int page = Integer.parseInt(single(params, "page", "0"));
            int requestedSize = Integer.parseInt(single(params, "size", "50"));
            int size = Math.min(requestedSize, 1000);
            if (page < 0 || !Set.of(20, 50, 200, 1000).contains(size)) { throw invalid(); }
            var sort = validateSort(definition, params.getOrDefault("sort", definition.defaultSort()));
            String q = single(params, "q", "").strip();
            var filters = new ArrayList<Filter>();
            for (String raw : params.getOrDefault("filter", List.of())) {
                var parts = raw.split(":", 3);
                if (parts.length != 3) { throw invalid(); }
                var op = FilterOperator.valueOf(parts[1]);
                Object value = switch (op) {
                    case in, nin, between -> csv(parts[2]);
                    default -> parts[2];
                };
                filters.add(validateFilter(definition, new Filter(parts[0], op, value)));
            }
            String fieldsValue = single(params, "fields", null);
            var fields = fieldsValue == null ? List.<String>of() : csv(fieldsValue);
            if (!definition.fields().containsAll(fields)) { throw invalid(); }
            return new ListQuery(page, size, sort, q, List.copyOf(filters), fields);
        } catch (IllegalArgumentException ex) { throw invalid(); }
    }
    private static String single(MultiValueMap<String, String> params, String key, String fallback) {
        var values = params.get(key);
        if (values == null) { return fallback; }
        if (values.size() != 1 || values.getFirst() == null) { throw invalid(); }
        return values.getFirst();
    }
    public static List<String> csv(String value) {
        var parts = Arrays.stream(value.split(",", -1)).map(String::strip).toList();
        if (parts.stream().anyMatch(String::isEmpty) || new HashSet<>(parts).size() != parts.size()) { throw invalid(); }
        return parts;
    }
    public static List<String> validateSort(ListDefinition definition, List<String> sort) {
        if (sort == null) { throw invalid(); }
        var result = new ArrayList<String>();
        var used = new HashSet<String>();
        for (String raw : sort) {
            if (raw == null) { throw invalid(); }
            var parts = raw.split(",", -1);
            if (parts.length > 2 || !definition.sorts().containsKey(parts[0]) || !used.add(parts[0])) { throw invalid(); }
            String direction = parts.length == 1 ? "asc" : parts[1];
            if (!Set.of("asc", "desc").contains(direction)) { throw invalid(); }
            result.add(parts[0] + "," + direction);
        }
        return List.copyOf(result);
    }
    public static Filter validateFilter(ListDefinition definition, Filter filter) {
        try {
            if (filter == null || filter.field() == null || filter.op() == null || filter.value() == null) { throw invalid(); }
            var field = definition.field(filter.field());
            if (!field.operators().contains(filter.op())) { throw invalid(); }
            Object value;
            if (filter.op() == FilterOperator.exists) { value = scalar(ListDefinition.Type.BOOLEAN, filter.value()); }
            else if (Set.of(FilterOperator.in, FilterOperator.nin, FilterOperator.between).contains(filter.op())) {
                if (!(filter.value() instanceof List<?> list) || list.isEmpty()) { throw invalid(); }
                var values = list.stream().map(v -> scalar(field.type(), v)).toList();
                if (filter.op() == FilterOperator.between) {
                    if (values.size() != 2 || compare(values.get(0), values.get(1)) > 0) { throw invalid(); }
                }
                value = values;
            } else { value = scalar(field.type(), filter.value()); }
            return new Filter(filter.field(), filter.op(), value);
        } catch (IllegalArgumentException | DateTimeException ex) { throw invalid(); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object left, Object right) { return ((Comparable) left).compareTo(right); }
    private static Object scalar(ListDefinition.Type type, Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) { throw invalid(); }
        String text = value.toString();
        if (text.isBlank()) { throw invalid(); }
        return switch (type) {
            case TEXT -> { if (!(value instanceof String)) { throw invalid(); } yield text; }
            case NUMBER -> new BigDecimal(text);
            case BOOLEAN -> { if (!Set.of("true", "false").contains(text)) { throw invalid(); } yield Boolean.valueOf(text); }
            case DATE -> LocalDate.parse(text).toString();
            case INSTANT -> Instant.parse(text).toString();
        };
    }
    public ListQuery withoutField(String name) {
        return new ListQuery(page, size, sort, q, filters.stream().filter(f -> !f.field().equals(name)).toList(), fields);
    }
}
