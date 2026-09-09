package com.agilityhub.core.shared.application.lists;

import com.agilityhub.core.shared.application.contract.ApiContracts.*;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.agilityhub.core.shared.application.lists.ListDefinition.Type.*;

class ListQueryTest {
    static final ListDefinition DEFINITION = new ListDefinition("example", Map.of(
            "name", new ListDefinition.Field("name", TEXT), "n", new ListDefinition.Field("n", NUMBER),
            "date", new ListDefinition.Field("date", DATE), "at", new ListDefinition.Field("at", INSTANT),
            "active", new ListDefinition.Field("active", BOOLEAN), "only", new ListDefinition.Field("only", TEXT, Set.of(FilterOperator.contains))),
            Map.of("name", "name"), List.of("name"), List.of("name", "n"), List.of("name"), List.of("name,asc"), Set.of("id", "name", "n"));
    static ListQuery parse(String... pairs) {
        var params = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) { params.add(pairs[i], pairs[i+1]); }
        return ListQuery.parse(DEFINITION, params);
    }
    @Test void T_03_08_defaultsProjectionCanonicalSortAndPageCap() {
        assertThat(parse().size()).isEqualTo(50); assertThat(parse().page()).isZero();
        assertThat(parse("size", "9000", "sort", "name", "fields", "id,name", "q", "  literal .*  ").size()).isEqualTo(1000);
        assertThat(parse("sort", "name").sort()).containsExactly("name,asc");
        assertThat(parse("size", "200", "sort", "name,desc").sort()).containsExactly("name,desc");
        assertThat(parse("filter", "n:between:1,2").filters().getFirst().value()).isEqualTo(List.of(new java.math.BigDecimal("1"), new java.math.BigDecimal("2")));
        assertThat(parse("filter", "at:eq:2026-09-09T12:34:56Z").filters().getFirst().value()).isEqualTo("2026-09-09T12:34:56Z");
        assertThat(parse("filter", "active:eq:false", "filter", "date:gte:2026-09-09").withoutField("active").filters()).hasSize(1);
        assertThat(DEFINITION.selectColumns(null)).containsExactly("name"); assertThat(DEFINITION.selectColumns("n,name")).containsExactly("n", "name");
    }
    @ParameterizedTest @ValueSource(strings = {"foo:eq:1", "name:no:1", "name:eq", "name:between:a,b", "only:eq:a", "n:eq:nan", "n:between:3,1", "n:between:1,2,3", "n:between:1", "n:in:", "name:in:a,,b", "name:in:a,a", "active:eq:yes", "name:exists:no", "date:eq:2026-99-01", "at:eq:2026-01-01", "name:eq:", "active:lt:true"})
    void T_03_08_invalidFiltersAreAlwaysCatalog400(String filter) { invalid(() -> parse("filter", filter)); }
    @ParameterizedTest @ValueSource(strings = {"size=-1", "size=0", "size=21", "size=no", "page=-1", "page=2147483648", "sort=unknown", "sort=name,down", "sort=name,asc,desc", "sort=", "fields=secret", "fields="})
    void T_03_08_invalidPaginationSortAndProjection(String input) { var pair = input.split("=", -1); invalid(() -> parse(pair)); }
    @Test void T_03_08_savedJsonFiltersRejectWrongTypesAndMalformedRequests() {
        invalid(() -> parse("page", "0", "page", "1")); invalid(() -> parse("sort", "name", "sort", "name"));
        invalid(() -> DEFINITION.selectColumns("unknown")); invalid(() -> ListQuery.validateSort(DEFINITION, null));
        invalid(() -> ListQuery.validateSort(DEFINITION, Arrays.asList((String)null)));
        for (Filter filter : Arrays.asList(null, new Filter(null, FilterOperator.eq, "a"), new Filter("name", null, "a"), new Filter("name", FilterOperator.eq, null),
                new Filter("name", FilterOperator.in, "a"), new Filter("name", FilterOperator.in, List.of()), new Filter("name", FilterOperator.eq, Map.of("$ne", "a")),
                new Filter("name", FilterOperator.eq, 5), new Filter("n", FilterOperator.eq, true))) {
            invalid(() -> ListQuery.validateFilter(DEFINITION, filter));
        }
        assertThat(ListQuery.validateFilter(DEFINITION, new Filter("active", FilterOperator.eq, true)).value()).isEqualTo(true);
        assertThat(ListQuery.validateFilter(DEFINITION, new Filter("n", FilterOperator.eq, 3)).value()).isEqualTo(new java.math.BigDecimal("3"));
    }
    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_FILTER));
    }
}
