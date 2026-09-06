package com.agilityhub.core.clubs.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.agilityhub.core.shared.domain.Money;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class CommonContracts {
    private CommonContracts() { }

    public enum ExportKind { LIST, MEMBER_DATA, ACCOUNTING }
    public enum ExportFormat { XLSX, PDF, ZIP }
    public enum ExportStatus { QUEUED, RUNNING, READY, FAILED, EXPIRED }
    public record ExportError(
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String message) { }
    @Schema(description = "Only caller-owned jobs; signed download URLs last five minutes. No storage keys or query credentials.")
    public record ExportJob(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) ExportKind kind,
            String listKey,
            @Schema(requiredMode = REQUIRED) ExportFormat format,
            @Schema(requiredMode = REQUIRED) ExportStatus status,
            Long rows,
            Integer progressPct,
            String fileName,
            String downloadUrl,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            Instant expiresAt,
            ExportError error) { }
    public record DataExportInput(
            @Schema(requiredMode = REQUIRED) @NotNull Boolean deliverToMember) { }
    public record SavedView(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED, format = "uuid") String ownerAccountId,
            @Schema(requiredMode = REQUIRED) String listKey,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) List<String> columns,
            @Schema(requiredMode = REQUIRED) List<Filter> filters,
            @Schema(requiredMode = REQUIRED) List<String> sort,
            @Schema(requiredMode = REQUIRED) boolean shared,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record SavedViewCreate(
            @Schema(requiredMode = REQUIRED) @NotBlank String listKey,
            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 40) String name,
            @Schema(requiredMode = REQUIRED) @NotNull List<String> columns,
            @Schema(requiredMode = REQUIRED) @NotNull List<Filter> filters,
            @Schema(requiredMode = REQUIRED) @NotNull List<String> sort,
            @Schema(requiredMode = REQUIRED) @NotNull Boolean shared) { }
    public record SavedViewUpdate(
            @Schema(requiredMode = REQUIRED) @NotBlank String listKey,
            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 40) String name,
            @Schema(requiredMode = REQUIRED) @NotNull List<String> columns,
            @Schema(requiredMode = REQUIRED) @NotNull List<Filter> filters,
            @Schema(requiredMode = REQUIRED) @NotNull List<String> sort,
            @Schema(requiredMode = REQUIRED) @NotNull Boolean shared,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }

}
