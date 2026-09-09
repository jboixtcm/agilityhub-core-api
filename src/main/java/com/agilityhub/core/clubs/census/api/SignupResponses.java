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
    public record SignupConfig(boolean enabled, @Schema(requiredMode = NOT_REQUIRED) String closedText,
            List<SignupStep> steps, List<SignupPlan> plans,
            @Schema(requiredMode = NOT_REQUIRED, description = "Omitted without BILLING") List<SignupPaymentMethod> paymentMethods,
            SignupTexts texts, SignupLegal legal, SignupCountryProfile countryProfile,
            @Schema(requiredMode = NOT_REQUIRED, description = "BILLING first-month choices from S04 §6") SignupUpfrontConfig upfront,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only present for an authenticated MEMBER adding a dog") SignupMember member) { }
    public record SignupPlan(@Schema(format = "uuid") String id,
            @Schema(allowableValues = {"MONTHLY", "PACK", "SINGLE_CLASS"}) String type,
            @Schema(requiredMode = NOT_REQUIRED, allowableValues = {"MONTHLY_FEE", "MAINTENANCE"}) String billingMode,
            String name, String description, String conditions,
            @Schema(requiredMode = NOT_REQUIRED) String offerLabel,
            @Schema(requiredMode = NOT_REQUIRED) SignupPrice price,
            @Schema(requiredMode = NOT_REQUIRED) Money entryFee,
            @Schema(requiredMode = NOT_REQUIRED) SignupPack pack,
            @Schema(requiredMode = NOT_REQUIRED) Money maintenanceFee) { }
    public record SignupPrice(@Schema(format = "uuid") String id, Money amount,
            @Schema(allowableValues = {"MONTHLY", "ONE_OFF"}) String periodicity) { }
    public record SignupPack(int sessions, int validityMonths) { }
    public record SignupPaymentMethod(PaymentMethodType type, String label,
            @Schema(requiredMode = NOT_REQUIRED) String mandateText, @Schema(requiredMode = NOT_REQUIRED) String instructions) { }
    public record SignupTexts(String freeTrainingConditions, String therapyIntro, String familyGroupIntro,
            String monthlyPaymentIntro, String paymentDay, String cashConditions, String imageConsent) { }
    public record SignupLegal(@Schema(format = "uri") String privacyPolicyUrl, String legalTextsVersion, String imageConsentText) { }
    public record SignupCountryProfile(String code, List<SignupRequests.SignupIdDocumentType> idDocumentTypes,
            boolean postalCodeLookup, String phonePrefix, String dateFormat, String timeFormat) { }
    public record SignupUpfrontConfig(int firstMonthSplitDay, LocalDate today, List<SignupFirstMonthChoice> firstMonthOptions) { }
    public record SignupFirstMonthChoice(FirstMonthOption option, LocalDate startDate, Money amountDue) { }
    public record SignupMember(@Schema(requiredMode = NOT_REQUIRED, format = "uuid") String planId,
            @Schema(requiredMode = NOT_REQUIRED) PaymentMethodView paymentMethodMasked, boolean consentsUpToDate) { }
    public record IdentityCheckResult(IdentityCheckOutcome result, @Schema(requiredMode = NOT_REQUIRED) String maskedEmail) { }
    public record Town(String name, String region) { }
    public record UploadUrl(@Schema(format = "uri") String uploadUrl, String fileKey, Instant expiresAt) { }
    public record FamilyGroupLookupResult(FamilyGroupLookupOutcome result, @Schema(requiredMode = NOT_REQUIRED) String holderDisplayName) { }
    public record UpfrontLine(@Schema(format = "uuid") String id, UpfrontConcept concept, Money amount, UpfrontStatus status,
            @Schema(requiredMode = NOT_REQUIRED) Money paidAmount, @Schema(requiredMode = NOT_REQUIRED) UpfrontProvider provider) { }
    public record SignupUpfront(List<UpfrontLine> lines, Money totalDue) { }
    public record SignupCheckout(boolean required) { }
    public record SignupResult(@Schema(format = "uuid") String memberId,
            @Schema(description = "24-hour HMAC capability bound to memberId; never log or expose outside the applicant flow") String signupToken,
            @Schema(requiredMode = NOT_REQUIRED, description = "Omitted without BILLING") SignupUpfront upfront, SignupCheckout checkout) { }
    public record AddDogSignupResult(@Schema(format = "uuid") String dogId,
            @Schema(requiredMode = NOT_REQUIRED, description = "Omitted without BILLING") SignupUpfront upfront, SignupCheckout checkout) { }
    public record SignupDocumentFile(String name, @Schema(format = "uri") String downloadUrl) { }
    public record SignupDocumentView(String type, DocumentState state, List<SignupDocumentFile> files) { }
    public record SignupDogView(@Schema(format = "uuid") String id, String name, Sex sex, String breed,
            @Schema(pattern = "\\d{4}-(0[1-9]|1[0-2])") String birthMonth, String chip,
            @Schema(requiredMode = NOT_REQUIRED) String notesToInstructors,
            DogStatus status, @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String levelId,
            List<SignupDocumentView> documents) { }
    public record SignupSubmission(Instant submittedAt, int pendingDays, boolean readmission, SignupSource source,
            String locale, @Schema(format = "uuid") String planIdRequested) { }
    public record SignupFamilyGroupView(FamilyGroupClaimStatus status,
            @Schema(requiredMode = NOT_REQUIRED) String holderName, @Schema(requiredMode = NOT_REQUIRED) String dogName,
            @Schema(requiredMode = NOT_REQUIRED) FamilyMember holder) { }
    public record SignupUpfrontReview(List<UpfrontLine> lines, Money totalDue, Money totalPaid) { }
    public record SignupProposals(@Schema(requiredMode = NOT_REQUIRED) LocalDate nextInvoiceDate,
            @Schema(format = "uuid") String planId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String priceId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String familyGroupId, List<LevelSummary> levels) { }
    public record MemberSignupView(Member member, List<SignupDogView> dogs, SignupSubmission signup,
            @Schema(requiredMode = NOT_REQUIRED) SignupFamilyGroupView familyGroupClaim,
            @Schema(requiredMode = NOT_REQUIRED) SignupUpfrontReview upfront,
            SignupProposals proposals, List<SignupWarning> warnings, long version) { }
    public record ValidationDryRun(@Schema(requiredMode = NOT_REQUIRED) SignupUpfrontReview upfront,
            @Schema(requiredMode = NOT_REQUIRED) SignupPrice price,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate nextInvoiceDate, List<SignupWarning> warnings) { }
    public record ValidationResult(@Schema(format = "uuid") String memberId, int number,
            @Schema(format = "uuid") String accountId, List<String> dogIds) { }
    public record RejectionResult(@Schema(format = "uuid") String memberId, MemberStatus status, List<String> dogIds) { }
}
