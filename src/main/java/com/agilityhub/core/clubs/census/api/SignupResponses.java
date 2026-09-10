package com.agilityhub.core.clubs.census.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.application.contract.SignupWarning;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static com.agilityhub.core.clubs.census.api.CensusResponses.*;
import static com.agilityhub.core.clubs.census.api.SignupRequests.FirstMonthOption;

/** S04 public and ADMIN aggregates; all displayed texts are resolved in the response locale. */
public final class SignupResponses {
    private SignupResponses() { }

    public enum SignupStep { PERSON, DOG, FAMILY_GROUP, PAYMENT }
    public enum SignupSource { PUBLIC, APP_ADD_DOG }
    public enum IdentityCheckOutcome { NEW, VERIFICATION_SENT, SIGNUP_ALREADY_PENDING, CONTACT_CLUB }
    public enum FamilyGroupLookupOutcome { FOUND, NOT_FOUND }
    public enum FamilyGroupClaimStatus { NONE, FOUND, NOT_FOUND_PENDING }
    public enum UpfrontConcept { ENTRY_FEE, FIRST_MONTH, PACK, ADDITIONAL_DOG_FEE }
    public enum UpfrontStatus { DUE, CHECKOUT_PENDING, PAID, PARTIAL, CANCELLED, REFUNDED }
    public enum UpfrontProvider { STRIPE, MANUAL }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupConfig(boolean enabled, @Schema(requiredMode = NOT_REQUIRED) String closedText,
            @Schema(requiredMode = NOT_REQUIRED, description = "Present when signup is enabled") List<SignupStep> steps, @Schema(requiredMode = NOT_REQUIRED) List<SignupPlan> plans,
            @Schema(requiredMode = NOT_REQUIRED, description = "Omitted without BILLING") List<SignupPaymentMethod> paymentMethods,
            @Schema(requiredMode = NOT_REQUIRED) SignupTexts texts, @Schema(requiredMode = NOT_REQUIRED) SignupLegal legal, @Schema(requiredMode = NOT_REQUIRED) SignupCountryProfile countryProfile,
            @Schema(requiredMode = NOT_REQUIRED, description = "BILLING first-month choices from S04 §6") SignupUpfrontConfig upfront,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only present for an authenticated MEMBER adding a dog") SignupMember member) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupPlan(@Schema(format = "uuid") String id,
            @Schema(allowableValues = {"MONTHLY", "PACK", "SINGLE_CLASS"}) String type,
            @Schema(requiredMode = NOT_REQUIRED, allowableValues = {"MONTHLY_FEE", "MAINTENANCE"}) String billingMode,
            String name, String description, String conditions,
            @Schema(requiredMode = NOT_REQUIRED) String offerLabel,
            @Schema(requiredMode = NOT_REQUIRED) SignupPrice price,
            @Schema(requiredMode = NOT_REQUIRED) Money entryFee,
            @Schema(requiredMode = NOT_REQUIRED) SignupPack pack,
            @Schema(requiredMode = NOT_REQUIRED) Money maintenanceFee) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupPrice(@Schema(format = "uuid") String id, Money amount,
            @Schema(allowableValues = {"MONTHLY", "ONE_OFF"}) String periodicity) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupPack(int sessions, int validityMonths) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupPaymentMethod(PaymentMethodType type, String label,
            @Schema(requiredMode = NOT_REQUIRED) String mandateText, @Schema(requiredMode = NOT_REQUIRED) String instructions) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupTexts(String freeTrainingConditions, String therapyIntro, String familyGroupIntro,
            String monthlyPaymentIntro, String paymentDay, String cashConditions, String imageConsent) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupLegal(@Schema(format = "uri") String privacyPolicyUrl, String legalTextsVersion, String imageConsentText) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupCountryProfile(String code, List<SignupRequests.SignupIdDocumentType> idDocumentTypes,
            boolean postalCodeLookup, String phonePrefix, String dateFormat, String timeFormat) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupUpfrontConfig(int firstMonthSplitDay, LocalDate today, List<SignupConfigChoice> firstMonthOptions,
            @Schema(requiredMode = NOT_REQUIRED) List<SignupConfigChoice> additionalDogOptions) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupConfigChoice(FirstMonthOption option, LocalDate startDate, Money amount) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupFirstMonthChoice(FirstMonthOption option, LocalDate startDate, Money amountDue) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupMember(@Schema(requiredMode = NOT_REQUIRED, format = "uuid") String planId,
            @Schema(requiredMode = NOT_REQUIRED) PaymentMethodView paymentMethodMasked, boolean consentsUpToDate) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record IdentityCheckResult(IdentityCheckOutcome result, @Schema(requiredMode = NOT_REQUIRED) String maskedEmail) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Town(String name, String region) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record UploadUrl(@Schema(format = "uri") String uploadUrl, String fileKey, Instant expiresAt) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record FamilyGroupLookupResult(FamilyGroupLookupOutcome result, @Schema(requiredMode = NOT_REQUIRED) String holderDisplayName) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record UpfrontLine(@Schema(format = "uuid") String id, UpfrontConcept concept, Money amount, UpfrontStatus status,
            @Schema(requiredMode = NOT_REQUIRED) Money paidAmount, @Schema(requiredMode = NOT_REQUIRED) UpfrontProvider provider) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupUpfront(List<UpfrontLine> lines, Money totalDue, @Schema(requiredMode = NOT_REQUIRED) SignupFirstMonthChoice additionalDog) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupCheckout(boolean required) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record AddDogCheckout(boolean required, @Schema(format = "uuid") String memberId) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupResult(@Schema(format = "uuid") String memberId,
            @Schema(description = "24-hour HMAC capability bound to memberId; never log or expose outside the applicant flow") String signupToken,
            @Schema(requiredMode = NOT_REQUIRED, description = "Omitted without BILLING") SignupUpfront upfront, SignupCheckout checkout) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record AddDogSignupResult(@Schema(format = "uuid") String dogId,
            @Schema(requiredMode = NOT_REQUIRED, description = "Omitted without BILLING") SignupUpfront upfront, AddDogCheckout checkout) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupDocumentFile(String name, @Schema(format = "uri") String downloadUrl) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupDocumentView(String type, DocumentState state, List<SignupDocumentFile> files) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupDogView(@Schema(format = "uuid") String id, String name, Sex sex, String breed,
            @Schema(pattern = "\\d{4}-(0[1-9]|1[0-2])") String birthMonth, String chip,
            @Schema(requiredMode = NOT_REQUIRED) String notesToInstructors,
            DogStatus status, @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String levelId,
            List<SignupDocumentView> documents) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupSubmission(Instant submittedAt, int pendingDays, boolean readmission, SignupSource source,
            String locale, @Schema(requiredMode = NOT_REQUIRED,format = "uuid") String planIdRequested) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupFamilyGroupView(FamilyGroupClaimStatus status,
            @Schema(requiredMode = NOT_REQUIRED) String holderName, @Schema(requiredMode = NOT_REQUIRED) String dogName,
            @Schema(requiredMode = NOT_REQUIRED) FamilyMember holder) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupUpfrontReview(List<UpfrontLine> lines, Money totalDue, Money totalPaid) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupProposals(@Schema(requiredMode = NOT_REQUIRED) LocalDate nextInvoiceDate,
            @Schema(requiredMode = NOT_REQUIRED,format = "uuid") String planId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String priceId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String familyGroupId, List<LevelSummary> levels) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record MemberSignupView(Member member, List<SignupDogView> dogs, SignupSubmission signup,
            @Schema(requiredMode = NOT_REQUIRED) SignupFamilyGroupView familyGroupClaim,
            @Schema(requiredMode = NOT_REQUIRED) SignupUpfrontReview upfront,
            SignupProposals proposals, List<SignupWarning> warnings, long version) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ValidationDryRun(@Schema(requiredMode = NOT_REQUIRED) SignupUpfrontReview upfront,
            @Schema(requiredMode = NOT_REQUIRED) SignupPrice price,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate nextInvoiceDate, List<SignupWarning> warnings) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ValidationResult(@Schema(format = "uuid") String memberId, int number,
            @Schema(format = "uuid") String accountId, List<String> dogIds) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RejectionResult(@Schema(format = "uuid") String memberId, MemberStatus status, List<String> dogIds, @Schema(requiredMode = NOT_REQUIRED, description = "A collected payment remains recorded and requires a refund through billing") Boolean paidPaymentRequiresRefund) { }
}
