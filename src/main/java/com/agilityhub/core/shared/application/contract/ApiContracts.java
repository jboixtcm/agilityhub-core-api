package com.agilityhub.core.shared.application.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** Reusable wire contracts; no persistence objects cross the API boundary. */
public final class ApiContracts {
    private ApiContracts() { }

    public record ListPage<T>(@Schema(requiredMode = REQUIRED) List<T> items,
            @Schema(requiredMode = REQUIRED, minimum = "0") int page,
            @Schema(requiredMode = REQUIRED, minimum = "1") int size,
            @Schema(requiredMode = REQUIRED, minimum = "0") long totalItems,
            @Schema(requiredMode = REQUIRED, minimum = "0") int totalPages,
            @Schema(requiredMode = REQUIRED) List<Filter> appliedFilters) { }
    public record CatalogItems<T>(@Schema(requiredMode = REQUIRED) List<T> items,
            @Schema(requiredMode = REQUIRED, minimum = "0") long totalItems) { }
    public record Filter(@Schema(requiredMode = REQUIRED) String field,
            @Schema(requiredMode = REQUIRED) FilterOperator op,
            @Schema(requiredMode = REQUIRED, description = "JSON scalar or array, according to the field and operator") Object value) { }
    public enum FilterOperator { eq, ne, in, nin, lt, lte, gt, gte, contains, startsWith, exists, between }
    public record FilterValues(@Schema(requiredMode = REQUIRED) String field,
            @Schema(requiredMode = REQUIRED) List<FilterValue> values) { }
    public record FilterValue(@Schema(requiredMode = REQUIRED) Object value,
            @Schema(requiredMode = REQUIRED) String label, @Schema(requiredMode = REQUIRED) long count) { }
    public record LastChange(@Schema(requiredMode = REQUIRED) Instant at,
            @Schema(requiredMode = NOT_REQUIRED) String actorName, @Schema(requiredMode = REQUIRED) String action) { }
    public record ExportAccepted(@Schema(requiredMode = REQUIRED, format = "uuid") String jobId,
            @Schema(requiredMode = REQUIRED, format = "uri") String statusUrl) { }
}
