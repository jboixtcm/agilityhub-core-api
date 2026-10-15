package com.agilityhub.core.courses.application;

import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.application.lists.ListDefinition.Type;
import com.agilityhub.core.shared.application.lists.ListQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * The universal list contract (CONVENCIONS_API §4) over the courses a caller may read. A library mixes the club's documents with
 * global ones (R-16-03), so it cannot start from the shared engine's tenant `$match`: the visible set is read without the models
 * (a few hundred rows at most) and the already-validated {@link ListQuery} runs here with the engine's semantics — exact `eq`,
 * case-insensitive literal `contains`/`startsWith`, a missing value matching `ne`/`nin`, an array field matching when any element
 * does, `q` over every translation of the name and the designer, and `id` as the last sort key.
 */
final class CourseListing {
    private CourseListing() { }
    /** One row: the item, its sort keys, its search text and its filter values by filter name. */
    record Row<T>(T item, String id, String name, Instant updatedAt, List<String> search, Map<String, Object> values) { }
    record Page<T>(List<T> items, long total) { }

    static <T> Page<T> page(ListQuery query, Map<String, Type> types, List<Row<T>> rows) {
        String q = query.q().toLowerCase(Locale.ROOT);
        var selected = rows.stream()
                .filter(row -> q.isEmpty() || row.search().stream().anyMatch(text -> text != null && text.toLowerCase(Locale.ROOT).contains(q)))
                .filter(row -> query.filters().stream().allMatch(filter -> matches(filter, types.get(filter.field()), row.values().get(filter.field()))))
                .sorted(order(query.sort())).toList();
        int from = (int) Math.min(selected.size(), (long) query.page() * query.size());
        int to = Math.min(selected.size(), from + query.size());
        return new Page<>(selected.subList(from, to).stream().map(Row::item).toList(), selected.size());
    }

    private static <T> Comparator<Row<T>> order(List<String> sort) {
        Comparator<Row<T>> order = (a, b) -> 0;
        for (String term : sort) {
            var parts = term.split(",");
            Comparator<Row<T>> key = parts[0].equals("name")
                    ? Comparator.comparing(Row::name, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                    : Comparator.comparing(Row::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
            order = order.thenComparing(parts[1].equals("desc") ? key.reversed() : key);
        }
        return order.thenComparing(Row::id);
    }

    static boolean matches(Filter filter, Type type, Object value) {
        var op = filter.op();
        if (op == com.agilityhub.core.shared.application.contract.ApiContracts.FilterOperator.exists) {
            boolean present = value != null && !(value instanceof Collection<?> list && list.isEmpty());
            return present == (Boolean) filter.value();
        }
        if (value instanceof Collection<?> list) {
            return switch (op) {
                case ne -> list.stream().noneMatch(item -> compare(type, item, filter.value()) == 0);
                case nin -> list.stream().noneMatch(item -> contained(type, item, filter.value()));
                default -> list.stream().anyMatch(item -> scalar(filter, type, item));
            };
        }
        return scalar(filter, type, value);
    }

    private static boolean scalar(Filter filter, Type type, Object value) {
        return switch (filter.op()) {
            case ne -> value == null || compare(type, value, filter.value()) != 0;
            case nin -> value == null || !contained(type, value, filter.value());
            default -> value != null && switch (filter.op()) {
                case eq -> compare(type, value, filter.value()) == 0;
                case in -> contained(type, value, filter.value());
                case lt -> compare(type, value, filter.value()) < 0;
                case lte -> compare(type, value, filter.value()) <= 0;
                case gt -> compare(type, value, filter.value()) > 0;
                case gte -> compare(type, value, filter.value()) >= 0;
                case between -> compare(type, value, ((List<?>) filter.value()).get(0)) >= 0 && compare(type, value, ((List<?>) filter.value()).get(1)) <= 0;
                case contains -> value.toString().toLowerCase(Locale.ROOT).contains(filter.value().toString().toLowerCase(Locale.ROOT));
                case startsWith -> value.toString().toLowerCase(Locale.ROOT).startsWith(filter.value().toString().toLowerCase(Locale.ROOT));
                default -> false;
            };
        };
    }
    private static boolean contained(Type type, Object value, Object values) {
        return ((List<?>) values).stream().anyMatch(item -> compare(type, value, item) == 0);
    }
    /** {@link ListQuery} gives a NUMBER as a BigDecimal and an INSTANT as its ISO text; TEXT compares exactly. */
    static int compare(Type type, Object value, Object target) {
        return switch (type) {
            case NUMBER -> new BigDecimal(value.toString()).compareTo(new BigDecimal(target.toString()));
            case INSTANT -> Instant.parse(value.toString()).compareTo(Instant.parse(target.toString()));
            default -> value.toString().compareTo(target.toString());
        };
    }
}
