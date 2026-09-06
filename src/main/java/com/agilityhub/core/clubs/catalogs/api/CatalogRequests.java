package com.agilityhub.core.clubs.catalogs.api;

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
import static com.agilityhub.core.clubs.catalogs.api.CatalogResponses.*;
/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class CatalogRequests {
    private CatalogRequests() { }

    public record LevelCreate(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 1, max = 8) @Pattern(regexp = "[A-Z0-9_]+") String code,
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> name,
            @Min(0) Integer order,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            @Min(1) @Max(99) Integer capacity,
            Boolean grantsFreeTraining,
            AgilityHubLevel agilityhubLevel,
            Boolean active) { }
    public record LevelPatch(
            @Size(min = 1, max = 8) @Pattern(regexp = "[A-Z0-9_]+") String code,
            Map<String, String> name,
            @Min(0) Integer order,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            @Min(1) @Max(99) Integer capacity,
            Boolean grantsFreeTraining,
            AgilityHubLevel agilityhubLevel,
            Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record RingCreate(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 40) String name,
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 2, max = 4) @Pattern(regexp = "[A-Z0-9]+") String shortName,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            Boolean allowsFreeTraining,
            @Min(1) @Max(20) Integer trainingCapacity,
            @Min(0) Integer order,
            Boolean active) { }
    public record RingPatch(
            @Size(max = 40) String name,
            @Size(min = 2, max = 4) @Pattern(regexp = "[A-Z0-9]+") String shortName,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            Boolean allowsFreeTraining,
            @Min(1) @Max(20) Integer trainingCapacity,
            @Min(0) Integer order,
            Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record PlanCreate(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 1, max = 16) @Pattern(regexp = "[A-Z0-9_]+") String code,
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> name,
            @Schema(requiredMode = REQUIRED) @NotNull PlanType type,
            @Min(1) @Max(9) Integer dogsIncluded,
            EntryFee entryFee,
            PackSettings pack,
            SingleClassSettings singleClass,
            Map<String, String> conditions,
            PlanTextsInput texts,
            Boolean showOnSignup,
            Boolean showOnWeb,
            @Min(0) Integer order,
            Boolean active) { }
    public record PlanPatch(
            @Size(min = 1, max = 16) @Pattern(regexp = "[A-Z0-9_]+") String code,
            Map<String, String> name,
            PlanType type,
            @Min(1) @Max(9) Integer dogsIncluded,
            EntryFee entryFee,
            PackSettings pack,
            SingleClassSettings singleClass,
            Map<String, String> conditions,
            PlanTextsInput texts,
            Boolean showOnSignup,
            Boolean showOnWeb,
            @Min(0) Integer order,
            Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record FaqCreate(
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> category,
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> question,
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> answer,
            @Min(0) Integer order,
            Boolean active) { }
    public record FaqPatch(
            Map<String, String> category,
            Map<String, String> question,
            Map<String, String> answer,
            @Min(0) Integer order,
            Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record PlanTextsInput(
            Map<String, String> description,
            Map<String, String> offerLabel,
            Map<String, String> priceLabel) { }
    public record LevelOrder(
            @Schema(requiredMode = REQUIRED) @NotNull List<String> levelIds) { }
    public record RingOrder(
            @Schema(requiredMode = REQUIRED) @NotNull List<String> ringIds) { }
    public record PlanOrder(
            @Schema(requiredMode = REQUIRED) @NotNull List<String> planIds) { }
    public record FaqOrder(
            @Schema(requiredMode = REQUIRED) @NotNull List<String> faqEntryIds) { }
    public record InstructorCreate(
            @Schema(requiredMode = REQUIRED) @NotBlank String memberId,
            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 12) String shortName,
            @Schema(requiredMode = REQUIRED) @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color) { }
    public record InstructorPatch(
            @Size(max = 12) String shortName,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record AdministratorCreate(
            @Schema(requiredMode = REQUIRED) @NotBlank String memberId,
            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 12) String shortName,
            @Schema(requiredMode = REQUIRED) @NotNull LocalDate since) { }
    public record AdministratorPatch(
            @Size(max = 12) String shortName,
            LocalDate since,
            Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record PriceCreate(
            @Schema(requiredMode = REQUIRED) @NotBlank String planId,
            @Schema(requiredMode = REQUIRED) @NotNull PriceConcept concept,
            @Schema(requiredMode = REQUIRED) @NotNull Money amount,
            @Schema(requiredMode = REQUIRED) @NotNull @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal taxPercent,
            @Schema(requiredMode = REQUIRED) @NotNull LocalDate validFrom,
            LocalDate validTo) { }
    public record PricePatch(
            PriceConcept concept,
            Money amount,
            @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal taxPercent,
            LocalDate validFrom,
            LocalDate validTo,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }

}
