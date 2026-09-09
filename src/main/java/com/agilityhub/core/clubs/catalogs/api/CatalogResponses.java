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
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class CatalogResponses {
    private CatalogResponses() { }

    public enum PlanType { MONTHLY, PACK, SINGLE_CLASS }
    public enum PriceConcept { MONTHLY_FEE, MAINTENANCE_FEE, PACK, SINGLE_CLASS }
    public enum PriceStatus { SCHEDULED, CURRENT, EXPIRED }
    public enum EntryFeeMode { STANDARD, AMOUNT, PERCENT, NONE }
    public enum ChargeMode { CHARGE_ON_ATTENDANCE, PAY_TO_BOOK }
    public enum CancelPolicy { REFUND, CREDIT, NONE }
    public record EntryFee(
            @Schema(requiredMode = REQUIRED) EntryFeeMode mode,
            @Schema(requiredMode = NOT_REQUIRED) Money amount,
            @Schema(requiredMode = NOT_REQUIRED) Integer percent) { }
    public record PackSettings(
            @Schema(requiredMode = REQUIRED) int sessions,
            @Schema(requiredMode = REQUIRED) int validityMonths) { }
    public record SingleClassSettings(
            @Schema(requiredMode = REQUIRED) ChargeMode chargeMode,
            @Schema(requiredMode = REQUIRED) CancelPolicy cancelPolicy) { }
    public record PlanTexts(
            @Schema(requiredMode = NOT_REQUIRED) String description,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> descriptionI18n,
            @Schema(requiredMode = NOT_REQUIRED) String offerLabel,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> offerLabelI18n,
            @Schema(requiredMode = NOT_REQUIRED) String priceLabel,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> priceLabelI18n) { }
    public record LevelUsage(
            @Schema(requiredMode = REQUIRED) int activeDogs,
            @Schema(requiredMode = REQUIRED) int futureClassSessions,
            @Schema(requiredMode = REQUIRED) int templateClasses) { }
    public record RingUsage(
            @Schema(requiredMode = REQUIRED) int futureClassSessions,
            @Schema(requiredMode = REQUIRED) int futureTrainingBookings,
            @Schema(requiredMode = REQUIRED) int templateClasses,
            @Schema(requiredMode = REQUIRED) int ringBlocks) { }
    public record InstructorUsage(
            @Schema(requiredMode = REQUIRED) int futureClassSessions,
            @Schema(requiredMode = REQUIRED) int templateClasses) { }
    public record PlanUsage(
            @Schema(requiredMode = REQUIRED) int members,
            @Schema(requiredMode = REQUIRED) int packBalances,
            @Schema(requiredMode = REQUIRED) int invoiceLines) { }
    @Schema(description = "ADMIN projection; reduced catalog readers omit usage and nameI18n.")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Level(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> nameI18n,
            @Schema(requiredMode = REQUIRED) int order,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED) int capacity,
            @Schema(requiredMode = REQUIRED) boolean grantsFreeTraining,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = NOT_REQUIRED) LevelUsage usage,
            @Schema(requiredMode = NOT_REQUIRED) LevelUsage warnings,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record LevelReaderView(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) int order,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED) int capacity,
            @Schema(requiredMode = REQUIRED) boolean grantsFreeTraining,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = REQUIRED) long version) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Ring(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String shortName,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED) boolean allowsFreeTraining,
            @Schema(requiredMode = NOT_REQUIRED) Integer trainingCapacity,
            @Schema(requiredMode = REQUIRED) int effectiveTrainingCapacity,
            @Schema(requiredMode = REQUIRED) int order,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = NOT_REQUIRED) RingUsage usage,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record RingReaderView(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String shortName,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED) boolean allowsFreeTraining,
            @Schema(requiredMode = REQUIRED) int effectiveTrainingCapacity,
            @Schema(requiredMode = REQUIRED) int order,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record Instructor(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED, format = "uuid") String memberId,
            @Schema(requiredMode = REQUIRED) String shortName,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = NOT_REQUIRED) InstructorUsage usage,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    @Schema(description = "Reduced reader projection has no memberId or usage.")
    public record InstructorReaderView(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String shortName,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = REQUIRED) long version) { }
    @Schema(description = "Membership adminProfile projection; no separate administrator entity.")
    public record Administrator(
            @Schema(requiredMode = REQUIRED, format = "uuid") String membershipId,
            @Schema(requiredMode = REQUIRED, format = "uuid") String memberId,
            @Schema(requiredMode = REQUIRED) String shortName,
            @Schema(requiredMode = REQUIRED) LocalDate since,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record Price(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED, format = "uuid") String planId,
            @Schema(requiredMode = REQUIRED) PriceConcept concept,
            @Schema(requiredMode = REQUIRED) Money amount,
            @Schema(requiredMode = REQUIRED) java.math.BigDecimal taxPercent,
            @Schema(requiredMode = REQUIRED) LocalDate validFrom,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate validTo,
            @Schema(requiredMode = REQUIRED) PriceStatus status,
            @Schema(requiredMode = REQUIRED) boolean locked,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record PriceCreated(
            @Schema(requiredMode = REQUIRED) Price price,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String closedPriceId) { }
    public record Plan(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> nameI18n,
            @Schema(requiredMode = REQUIRED) PlanType type,
            @Schema(requiredMode = REQUIRED) int dogsIncluded,
            @Schema(requiredMode = REQUIRED) EntryFee entryFee,
            @Schema(requiredMode = NOT_REQUIRED) PackSettings pack,
            @Schema(requiredMode = NOT_REQUIRED) SingleClassSettings singleClass,
            @Schema(requiredMode = NOT_REQUIRED) String conditions,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> conditionsI18n,
            @Schema(requiredMode = NOT_REQUIRED) PlanTexts texts,
            @Schema(requiredMode = REQUIRED) boolean showOnSignup,
            @Schema(requiredMode = REQUIRED) boolean showOnWeb,
            @Schema(requiredMode = REQUIRED) int order,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = NOT_REQUIRED) List<Price> currentPrices,
            @Schema(requiredMode = NOT_REQUIRED) List<Price> prices,
            @Schema(requiredMode = NOT_REQUIRED) PlanUsage usage,
            @Schema(requiredMode = NOT_REQUIRED) Money entryFeeAmount,
            @Schema(requiredMode = NOT_REQUIRED) List<String> warnings,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record PlanReaderView(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) PlanType type,
            @Schema(requiredMode = REQUIRED) int dogsIncluded,
            @Schema(requiredMode = REQUIRED) EntryFee entryFee,
            @Schema(requiredMode = NOT_REQUIRED) PackSettings pack,
            @Schema(requiredMode = NOT_REQUIRED) SingleClassSettings singleClass,
            @Schema(requiredMode = NOT_REQUIRED) String conditions,
            @Schema(requiredMode = NOT_REQUIRED) PublicPlanTexts texts,
            @Schema(requiredMode = REQUIRED) boolean showOnSignup,
            @Schema(requiredMode = REQUIRED) boolean showOnWeb,
            @Schema(requiredMode = REQUIRED) int order,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = NOT_REQUIRED) List<PublicPrice> currentPrices,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record PublicPrice(
            @Schema(requiredMode = REQUIRED) PriceConcept concept,
            @Schema(requiredMode = REQUIRED) Money amount) { }
    public record PublicPlanTexts(
            @Schema(requiredMode = NOT_REQUIRED) String description,
            @Schema(requiredMode = NOT_REQUIRED) String offerLabel,
            @Schema(requiredMode = NOT_REQUIRED) String priceLabel) { }
    @Schema(description = "Only active showOnWeb plans; prices omitted when BILLING is disabled.")
    public record PublicPlan(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) PlanType type,
            @Schema(requiredMode = REQUIRED) int dogsIncluded,
            @Schema(requiredMode = NOT_REQUIRED) String conditions,
            @Schema(requiredMode = NOT_REQUIRED) PublicPlanTexts texts,
            @Schema(requiredMode = NOT_REQUIRED) List<PublicPrice> currentPrices,
            @Schema(requiredMode = NOT_REQUIRED) Money entryFeeAmount,
            @Schema(requiredMode = REQUIRED) int order) { }
    public record PublicPlansClub(
            @Schema(requiredMode = REQUIRED) String slug,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String currency) { }
    public record PublicPlans(
            @Schema(requiredMode = REQUIRED) PublicPlansClub club,
            @Schema(requiredMode = REQUIRED) List<PublicPlan> plans) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record FaqEntry(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String category,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> categoryI18n,
            @Schema(requiredMode = REQUIRED) String question,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> questionI18n,
            @Schema(requiredMode = REQUIRED) String answer,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> answerI18n,
            @Schema(requiredMode = REQUIRED) int order,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record FaqReaderView(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String category,
            @Schema(requiredMode = REQUIRED) String question,
            @Schema(requiredMode = REQUIRED) String answer,
            @Schema(requiredMode = REQUIRED) int order,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = REQUIRED) long version) { }

}
