package com.agilityhub.core.courses.application;

import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterOperator;
import com.agilityhub.core.shared.application.lists.ListDefinition.Type;
import com.agilityhub.core.shared.application.lists.ListQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.assertThat;
import static com.agilityhub.core.shared.application.contract.ApiContracts.FilterOperator.*;

/** E9-T02: the universal list semantics of the course library (CONVENCIONS_API §4) over the visible courses. */
class CourseListingTest {
    static Filter f(FilterOperator op, Object value) { return new Filter("x", op, value); }

    @Test void T_16_05_scalarFiltersFollowTheEngine() {
        assertThat(CourseListing.matches(f(eq, "A"), Type.TEXT, "A")).isTrue();
        assertThat(CourseListing.matches(f(eq, "A"), Type.TEXT, "a")).isFalse();
        assertThat(CourseListing.matches(f(eq, "A"), Type.TEXT, null)).isFalse();
        assertThat(CourseListing.matches(f(ne, "A"), Type.TEXT, null)).isTrue();
        assertThat(CourseListing.matches(f(ne, "A"), Type.TEXT, "A")).isFalse();
        assertThat(CourseListing.matches(f(in, List.of("A", "B")), Type.TEXT, "B")).isTrue();
        assertThat(CourseListing.matches(f(nin, List.of("A", "B")), Type.TEXT, "B")).isFalse();
        assertThat(CourseListing.matches(f(nin, List.of("A")), Type.TEXT, null)).isTrue();
        assertThat(CourseListing.matches(f(contains, "nna"), Type.TEXT, "ANNA")).isTrue();
        assertThat(CourseListing.matches(f(startsWith, "an"), Type.TEXT, "Anna")).isTrue();
        assertThat(CourseListing.matches(f(startsWith, "nn"), Type.TEXT, "Anna")).isFalse();
        assertThat(CourseListing.matches(f(lt, new BigDecimal("3")), Type.NUMBER, 2)).isTrue();
        assertThat(CourseListing.matches(f(lte, new BigDecimal("3")), Type.NUMBER, 3)).isTrue();
        assertThat(CourseListing.matches(f(gt, new BigDecimal("3")), Type.NUMBER, 3)).isFalse();
        assertThat(CourseListing.matches(f(gte, new BigDecimal("3")), Type.NUMBER, 3)).isTrue();
        assertThat(CourseListing.matches(f(between, List.of(new BigDecimal("1"), new BigDecimal("2"))), Type.NUMBER, 2)).isTrue();
        assertThat(CourseListing.matches(f(between, List.of(new BigDecimal("1"), new BigDecimal("2"))), Type.NUMBER, 3)).isFalse();
        assertThat(CourseListing.matches(f(gt, "2026-10-01T00:00:00Z"), Type.INSTANT, "2026-10-02T00:00:00Z")).isTrue();
        assertThat(CourseListing.matches(f(lt, new BigDecimal("3")), Type.NUMBER, null)).isFalse();
        assertThat(CourseListing.matches(f(exists, true), Type.TEXT, "x")).isTrue();
        assertThat(CourseListing.matches(f(exists, true), Type.TEXT, null)).isFalse();
        assertThat(CourseListing.matches(f(exists, false), Type.TEXT, List.of())).isTrue();
    }

    @Test void T_16_05_anArrayFieldMatchesWhenAnyElementDoes() {
        var tags = List.of("tecnic", "salts");
        assertThat(CourseListing.matches(f(eq, "salts"), Type.TEXT, tags)).isTrue();
        assertThat(CourseListing.matches(f(ne, "salts"), Type.TEXT, tags)).isFalse();
        assertThat(CourseListing.matches(f(ne, "altre"), Type.TEXT, tags)).isTrue();
        assertThat(CourseListing.matches(f(in, List.of("x", "tecnic")), Type.TEXT, tags)).isTrue();
        assertThat(CourseListing.matches(f(nin, List.of("x", "tecnic")), Type.TEXT, tags)).isFalse();
        assertThat(CourseListing.matches(f(nin, List.of("x")), Type.TEXT, tags)).isTrue();
        assertThat(CourseListing.matches(f(contains, "ALT"), Type.TEXT, tags)).isTrue();
        assertThat(CourseListing.matches(f(exists, true), Type.TEXT, tags)).isTrue();
    }

    @Test void T_16_05_searchSortAndPagesWithTheIdAsTheLastKey() {
        var t = Instant.parse("2026-10-01T00:00:00Z");
        var rows = List.of(
                new CourseListing.Row<>("b", "b", "bravo", t.plusSeconds(1), List.of("Bravo"), Map.<String, Object>of("n", 2)),
                new CourseListing.Row<>("a", "a", "Alfa", t, Arrays.asList("Alfa", null), Map.<String, Object>of("n", 5)),
                new CourseListing.Row<>("c", "c", null, null, List.of("Charlie", "Designer"), Map.<String, Object>of("n", 5)),
                new CourseListing.Row<>("d", "d", "alfa", t, List.of("alfa"), Map.<String, Object>of("n", 1)));
        var types = Map.of("n", Type.NUMBER);
        assertThat(page(types, rows, "sort", "name,asc").items()).containsExactly("c", "a", "d", "b");
        assertThat(page(types, rows, "sort", "name,desc").items()).containsExactly("b", "a", "d", "c");
        assertThat(page(types, rows, "sort", "updatedAt,desc").items()).containsExactly("b", "a", "d", "c");
        assertThat(page(types, rows, "q", "DESIGN").items()).containsExactly("c");
        var filtered = CourseListing.page(new ListQuery(0, 20, List.of("updatedAt,asc"), "", List.of(new Filter("n", gte, new BigDecimal("5"))), List.of()), types, rows);
        assertThat(filtered.items()).containsExactly("c", "a");
        var second = CourseListing.page(new ListQuery(1, 20, List.of("updatedAt,asc"), "", List.of(), List.of()), types, rows);
        assertThat(second.items()).isEmpty();
        assertThat(second.total()).isEqualTo(4);
    }
    static CourseListing.Page<String> page(Map<String, Type> types, List<CourseListing.Row<String>> rows, String key, String value) {
        var params = new LinkedMultiValueMap<String, String>(); params.add(key, value);
        var query = ListQuery.parse(CourseListContract.COURSES, params);
        return CourseListing.page(query, types, rows);
    }
}
