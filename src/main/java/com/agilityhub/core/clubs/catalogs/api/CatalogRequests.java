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
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class CatalogRequests {
    private CatalogRequests() { }

    public record LevelCreate(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 1, max = 8) @Pattern(regexp = "[A-Za-z0-9_]+") String code,
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> name,
            @Schema(requiredMode = NOT_REQUIRED) @Min(0) Integer order,
            @Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            @Schema(requiredMode = NOT_REQUIRED) @Min(1) @Max(99) Integer capacity,
            @Schema(requiredMode = NOT_REQUIRED) Boolean grantsFreeTraining,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active) { }
    public record LevelPatch(
            @Schema(requiredMode = NOT_REQUIRED) @Size(min = 1, max = 8) @Pattern(regexp = "[A-Za-z0-9_]+") String code,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> name,
            @Schema(requiredMode = NOT_REQUIRED) @Min(0) Integer order,
            @Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            @Schema(requiredMode = NOT_REQUIRED) @Min(1) @Max(99) Integer capacity,
            @Schema(requiredMode = NOT_REQUIRED) Boolean grantsFreeTraining,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record RingCreate(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 40) String name,
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 2, max = 4) @Pattern(regexp = "[A-Za-z0-9]+") String shortName,
            @Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            @Schema(requiredMode = NOT_REQUIRED) Boolean allowsFreeTraining,
            @Schema(requiredMode = NOT_REQUIRED) @Min(1) @Max(20) Integer trainingCapacity,
            @Schema(requiredMode = NOT_REQUIRED) @Min(0) Integer order,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active) { }
    public record RingPatch(
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 40) String name,
            @Schema(requiredMode = NOT_REQUIRED) @Size(min = 2, max = 4) @Pattern(regexp = "[A-Za-z0-9]+") String shortName,
            @Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            @Schema(requiredMode = NOT_REQUIRED) Boolean allowsFreeTraining,
            @Schema(requiredMode = NOT_REQUIRED, implementation = Integer.class, types = {"integer", "null"}, minimum = "1", maximum = "20") com.fasterxml.jackson.databind.JsonNode trainingCapacity,
            @Schema(requiredMode = NOT_REQUIRED) @Min(0) Integer order,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record PlanCreate(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 1, max = 16) @Pattern(regexp = "[A-Z0-9_]+") String code,
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> name,
            @Schema(requiredMode = REQUIRED) @NotNull PlanType type,
            @Schema(requiredMode = NOT_REQUIRED) @Min(1) @Max(9) Integer dogsIncluded,
            @Schema(requiredMode = NOT_REQUIRED) EntryFee entryFee,
            @Schema(requiredMode = NOT_REQUIRED) PackSettings pack,
            @Schema(requiredMode = NOT_REQUIRED) SingleClassSettings singleClass,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> conditions,
            @Schema(requiredMode = NOT_REQUIRED) PlanTextsInput texts,
            @Schema(requiredMode = NOT_REQUIRED) Boolean showOnSignup,
            @Schema(requiredMode = NOT_REQUIRED) Boolean showOnWeb,
            @Schema(requiredMode = NOT_REQUIRED) @Min(0) Integer order,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active) { }
    public record PlanPatch(
            @Schema(requiredMode = NOT_REQUIRED) @Size(min = 1, max = 16) @Pattern(regexp = "[A-Z0-9_]+") String code,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> name,
            @Schema(requiredMode = NOT_REQUIRED) PlanType type,
            @Schema(requiredMode = NOT_REQUIRED) @Min(1) @Max(9) Integer dogsIncluded,
            @Schema(requiredMode = NOT_REQUIRED) EntryFee entryFee,
            @Schema(requiredMode = NOT_REQUIRED) PackSettings pack,
            @Schema(requiredMode = NOT_REQUIRED) SingleClassSettings singleClass,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> conditions,
            @Schema(requiredMode = NOT_REQUIRED) PlanTextsInput texts,
            @Schema(requiredMode = NOT_REQUIRED) Boolean showOnSignup,
            @Schema(requiredMode = NOT_REQUIRED) Boolean showOnWeb,
            @Schema(requiredMode = NOT_REQUIRED) @Min(0) Integer order,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record FaqCreate(
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> category,
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> question,
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, String> answer,
            @Schema(requiredMode = NOT_REQUIRED) @Min(0) Integer order,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active) { }
    public record FaqPatch(
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> category,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> question,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> answer,
            @Schema(requiredMode = NOT_REQUIRED) @Min(0) Integer order,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record PlanTextsInput(
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> description,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> offerLabel,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> priceLabel) { }
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
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 12) String shortName,
            @Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record AdministratorCreate(
            @Schema(requiredMode = REQUIRED) @NotBlank String memberId,
            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 12) String shortName,
            @Schema(requiredMode = REQUIRED) @NotNull LocalDate since) { }
    public record AdministratorPatch(
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 12) String shortName,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate since,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record PriceCreate(
            @Schema(requiredMode = REQUIRED) @NotBlank String planId,
            @Schema(requiredMode = REQUIRED) @NotNull PriceConcept concept,
            @Schema(requiredMode = REQUIRED) @NotNull Money amount,
            @Schema(requiredMode = REQUIRED) @NotNull @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal taxPercent,
            @Schema(requiredMode = REQUIRED) @NotNull LocalDate validFrom,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate validTo) { }
    public record PricePatch(
            @Schema(requiredMode = NOT_REQUIRED) PriceConcept concept,
            @Schema(requiredMode = NOT_REQUIRED) Money amount,
            @Schema(requiredMode = NOT_REQUIRED) @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal taxPercent,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate validFrom,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate validTo,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }

}
