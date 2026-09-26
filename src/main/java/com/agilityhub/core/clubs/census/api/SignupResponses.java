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
            @Schema(requiredMode = NOT_REQUIRED, description = "Only present for an authenticated MEMBER adding a dog") SignupMember member,
            @Schema(requiredMode = NOT_REQUIRED, description = "signup.allowFamilyGroupPending; present with FAMILY_GROUP. The server enforces it at submission too") Boolean allowFamilyGroupPending,
            @Schema(requiredMode = NOT_REQUIRED, description = "signup.requireDogDocumentAtSignup; present when signup is enabled. The server enforces it at submission too") Boolean requireDogDocumentAtSignup) { }
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
    @Schema(description = "Club texts resolved in the response locale, placeholders already interpolated by the server ({deadlineDay}, {twoDogsMonthlyFee})")
    public record SignupTexts(String freeTrainingConditions, String therapyIntro,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "null when the club has no family fare (R-04-13)") String familyGroupIntro,
            String monthlyPaymentIntro, String paymentDay, String cashConditions, String imageConsent) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupLegal(@Schema(format = "uri") String privacyPolicyUrl, String legalTextsVersion, String imageConsentText) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupCountryProfile(String code, List<SignupRequests.SignupIdDocumentType> idDocumentTypes,
            boolean postalCodeLookup, String phonePrefix, String dateFormat, String timeFormat) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupUpfrontConfig(int firstMonthSplitDay, LocalDate today,
            @Schema(description = "Deprecated by planQuotes: the options of the first monthly plan only") List<SignupConfigChoice> firstMonthOptions,
            @Schema(requiredMode = NOT_REQUIRED, description = "Add-dog mode: TODAY always, ALTERNATIVE only up to billing.upfrontCutoffDay") List<SignupConfigChoice> additionalDogOptions,
            @Schema(description = "R-04-14/15: one quote per offered plan (add-dog mode: also the member's own plan), computed like the submission") List<SignupPlanQuote> planQuotes) { }
    public enum QuotePortion { FULL, HALF }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupQuoteLine(UpfrontConcept concept, Money amount) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupQuoteOption(FirstMonthOption option, QuotePortion portion, LocalDate startDate, Money amountDue,
            @Schema(description = "The plan's lines + this option") Money totalDue) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupPlanQuote(@Schema(format = "uuid") String planId,
            @Schema(description = "The option-independent lines (ENTRY_FEE only if > 0, PACK)") List<SignupQuoteLine> lines,
            @Schema(description = "Sum of lines; the payable total of a plan without options") Money totalDue,
            @Schema(description = "Public signup: the two first-month options of a MONTHLY_FEE plan with a current price; add-dog: the additional-dog options; empty otherwise") List<SignupQuoteOption> options) { }
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
    public record UploadUrl(@Schema(format = "uri") String uploadUrl, String fileKey, Instant expiresAt,
            @Schema(description = "Headers the storage signed (Content-Type, If-None-Match: *); the PUT must send them unchanged, or S3 answers 403 (R-04-08)") java.util.Map<String, String> headers) { }
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
    public record SignupDocumentFile(String name, @Schema(format = "uri") String downloadUrl,
            @Schema(description = "R-04-19 (E5-T19): the file's key. A D2 PATCH /dogs/{id} that sends it back in documents[].files[] keeps this file "
                    + "with its stored name (the name sent with it is not applied); the type's files it does not send are removed") String fileKey) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupDocumentView(String type, DocumentState state, List<SignupDocumentFile> files) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupDogView(@Schema(format = "uuid") String id, String name, Sex sex, String breed,
            @Schema(pattern = "\\d{4}-(0[1-9]|1[0-2])") String birthMonth, String chip,
            @Schema(requiredMode = NOT_REQUIRED) String notesToInstructors,
            DogStatus status, @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String levelId,
            List<SignupDocumentView> documents,
            @Schema(description = "The dog's own optimistic version, compared by PATCH /dogs/{id}") long version,
            @Schema(requiredMode = NOT_REQUIRED, description = "R-04-06 (E38): only for the reused dog of a pending readmission (the chip of the member's own INACTIVE dog). "
                    + "The fields above show the submitted values and the documents the validation will write; the dog record keeps its own until then, and a rejection leaves it as it was") SignupDogReadmission readmission) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "R-04-06 (E38): the reused dog's own values beside the submitted ones shown on SignupDogView")
    public record SignupDogReadmission(SignupDogValues current,
            @Schema(description = "The fields whose submitted value differs from the dog record (name, sex, breed, birthMonth, notesToInstructors, documents)") List<String> changedFields,
            @Schema(requiredMode = NOT_REQUIRED) Instant previousDeactivatedAt, @Schema(requiredMode = NOT_REQUIRED) String previousDeactivationReason) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "The values and documents of the dog record")
    public record SignupDogValues(String name, @Schema(requiredMode = NOT_REQUIRED) Sex sex, @Schema(requiredMode = NOT_REQUIRED) String breed,
            @Schema(requiredMode = NOT_REQUIRED, pattern = "\\d{4}-(0[1-9]|1[0-2])") String birthMonth,
            @Schema(requiredMode = NOT_REQUIRED) String notesToInstructors, List<SignupDocumentView> documents) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupSubmission(Instant submittedAt, int pendingDays, boolean readmission, SignupSource source,
            String locale, @Schema(requiredMode = NOT_REQUIRED,format = "uuid") String planIdRequested) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupFamilyGroupView(FamilyGroupClaimStatus status,
            @Schema(requiredMode = NOT_REQUIRED) String holderName, @Schema(requiredMode = NOT_REQUIRED) String dogName,
            @Schema(requiredMode = NOT_REQUIRED) FamilyMember holder) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupUpfrontReview(List<UpfrontLine> lines, Money totalDue, Money totalPaid,
            @Schema(requiredMode = NOT_REQUIRED, description = "dryRun only (S04 §5, E39b): what was paid beyond the new plan's quote; warning PAID_EXCEEDS_QUOTE") Money paidExceedsQuote,
            @Schema(requiredMode = NOT_REQUIRED, description = "R-04-15: the month the FIRST_MONTH line pays; absent without that line. The D2 view gives the values frozen at submission (signup.upfront.firstMonth); the dryRun the ones the validation will charge (recalculated after a plan change)") SignupFirstMonth firstMonth) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "The first month of a public signup (R-04-15): its option, full or half month, start date and amount")
    public record SignupFirstMonth(FirstMonthOption option,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, description = "Frozen at submission. null only for a first month frozen before the portion was stored whose amount does not tell it against the plan's monthly price on the submission day") QuotePortion portion,
            LocalDate startDate, Money amountDue) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupProposals(@Schema(requiredMode = NOT_REQUIRED) LocalDate nextInvoiceDate,
            @Schema(requiredMode = NOT_REQUIRED,format = "uuid") String planId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String priceId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String familyGroupId, List<LevelSummary> levels) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record MemberSignupView(Member member, List<SignupDogView> dogs, SignupSubmission signup,
            @Schema(requiredMode = NOT_REQUIRED) SignupFamilyGroupView familyGroupClaim,
            @Schema(requiredMode = NOT_REQUIRED) SignupUpfrontReview upfront,
            SignupProposals proposals, List<SignupWarning> warnings,
            @Schema(description = "The assignable plans (active, module enabled, showOnSignup or not) for the D2 plan selector") List<SignupPlanOption> planOptions,
            @Schema(description = "R-04-10/R-04-19 (E3-T14): the D2 method selector. For a PENDING member: the methods of the club's enabled providers, as GET /signup offers them (assignable), plus the applicant's current method when its provider is off since (assignable = false, listed last); during a readmission, current is the submitted method. For an add-dog (member not PENDING) D2 cannot change the method: only the current one, assignable = false. Empty without BILLING") List<SignupPaymentMethodOption> paymentMethods,
            @Schema(description = "dashboard.pendingSignupAgeWarnDays: the age warning shows when signup.pendingDays > warnDays") int warnDays,
            long version,
            @Schema(requiredMode = NOT_REQUIRED, description = "R-04-06 (E38): only for a pending readmission. The LEFT record keeps its values until validation, which applies the submitted ones") SignupReadmission readmission) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "R-04-06 (E38): the values of the LEFT record and the values the readmission submitted, side by side; payment methods are masked")
    public record SignupReadmission(ReadmissionValues current, ReadmissionValues submitted,
            @Schema(description = "The fields whose submitted value differs from the record (firstName, lastName1, lastName2, gender, birthDate, contactEmails, phones, address, paymentMethod)") List<String> changedFields,
            @Schema(requiredMode = NOT_REQUIRED, description = "The consent entries the validation appends to the ledger") List<ReadmissionConsent> consents,
            @Schema(requiredMode = NOT_REQUIRED) Instant previousLeftAt, @Schema(requiredMode = NOT_REQUIRED) String previousLeftReason) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ReadmissionValues(String firstName, String lastName1, @Schema(requiredMode = NOT_REQUIRED) String lastName2,
            @Schema(requiredMode = NOT_REQUIRED) Gender gender, @Schema(requiredMode = NOT_REQUIRED) LocalDate birthDate,
            List<ContactEmail> contactEmails, List<Phone> phones, @Schema(requiredMode = NOT_REQUIRED) Address address,
            @Schema(requiredMode = NOT_REQUIRED) PaymentMethodView paymentMethod) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ReadmissionConsent(@Schema(allowableValues = {"PRIVACY_POLICY", "IMAGE_USE"}) String type, boolean granted, String version) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupPlanOptionPrice(@Schema(format = "uuid") String priceId, Money amount,
            @Schema(allowableValues = {"MONTHLY", "ONE_OFF"}) String periodicity,
            @Schema(allowableValues = {"MONTHLY_FEE", "MAINTENANCE_FEE", "PACK", "SINGLE_CLASS"}) String concept) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupPlanOption(@Schema(format = "uuid") String planId, String name,
            @Schema(allowableValues = {"MONTHLY", "PACK", "SINGLE_CLASS"}) String type,
            @Schema(description = "The current price the plan bills (its billing mode: MAINTENANCE_FEE for a MAINTENANCE plan); the priceId validation accepts. Empty without BILLING or without a current price") List<SignupPlanOptionPrice> prices) { }
    @Schema(description = "A D2 payment method option (E3-T14): `current` marks the applicant's method; `assignable = false` for a current method whose provider is no longer enabled (PATCH answers 422 PAYMENT_METHOD_NOT_AVAILABLE) and for the method of a member who is not PENDING (PATCH answers 400 VALIDATION_ERROR, paymentMethod READ_ONLY)")
    public record SignupPaymentMethodOption(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) PaymentMethodType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Resolved in the response locale, as GET /signup") String label,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean current, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean assignable) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ValidationDryRun(@Schema(requiredMode = NOT_REQUIRED) SignupUpfrontReview upfront,
            @Schema(requiredMode = NOT_REQUIRED) SignupPrice price,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate nextInvoiceDate, List<SignupWarning> warnings) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ValidationResult(@Schema(format = "uuid") String memberId, int number,
            @Schema(format = "uuid") String accountId, List<String> dogIds,
            @Schema(description = "PAID_EXCEEDS_QUOTE when the plan change left more paid than the new quote (S04 §5, E39b)") List<SignupWarning> warnings,
            @Schema(requiredMode = NOT_REQUIRED, description = "The amount paid beyond the new quote; the refund is S12's") Money paidExceedsQuote) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RejectionResult(@Schema(format = "uuid") String memberId, MemberStatus status, List<String> dogIds, @Schema(requiredMode = NOT_REQUIRED, description = "A collected payment remains recorded and requires a refund through billing") Boolean paidPaymentRequiresRefund) { }
}
