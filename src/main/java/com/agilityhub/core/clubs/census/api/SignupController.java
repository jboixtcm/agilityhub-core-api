package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.census.api.SignupRequests.*;
import static com.agilityhub.core.clubs.census.api.SignupResponses.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S04 contract stubs. E3-T03 supplies the transactional use cases. */
@RestController
public class SignupController {
    private static final String SIGNUP_EXAMPLE = """
{
  "locale": "en",
  "website": "",
  "person": {
    "idDocument": {
      "type": "DNI",
      "value": "12345678Z"
    },
    "firstName": "Example",
    "lastName1": "Member",
    "lastName2": "Second",
    "birthDate": "1991-05-08",
    "gender": "FEMALE",
    "emails": [
      "member@example.test"
    ],
    "phones": [
      {
        "prefix": "+34",
        "number": "600000001",
        "label": "Primary"
      }
    ],
    "address": {
      "street": "1 Example Street",
      "postalCode": "08001",
      "town": "Example Town"
    }
  },
  "dog": {
    "name": "Example Dog",
    "sex": "FEMALE",
    "breed": "Whippet",
    "birthMonth": "2024-04",
    "chip": "941000000000001",
    "notesToInstructors": "Example note",
    "documents": [
      {
        "type": "VACCINATION_CARD",
        "files": [
          {
            "fileKey": "signup/fictional/vaccination.jpg",
            "name": "vaccination.jpg"
          }
        ]
      }
    ]
  },
  "planId": "11111111-1111-4111-8111-111111111111",
  "familyGroupClaim": {
    "holderName": "Example Holder",
    "dogName": "Example Dog",
    "leavePending": false
  },
  "payment": {
    "type": "SEPA_DD",
    "iban": "ES0000000000000000000000",
    "holderName": "Example Member",
    "holderTaxId": null,
    "firstMonthOption": "TODAY"
  },
  "consents": {
    "privacyPolicy": {
      "accepted": true,
      "version": "2026-09"
    },
    "imageUse": {
      "granted": false,
      "version": "2026-09"
    }
  }
}
            """;
    private static final String PUBLIC = " Tenant by host. No cookies or CSRF. R-04-20 limits are per club and client IP from proxy-injected X-Forwarded-For; enforcement is E3-T03. Standard 501 stub.";

    @GetMapping("/api/v1/signup")
    @PreAuthorize("isAnonymous() or hasRole('MEMBER')")
    @SecurityRequirements
    @Operation(summary = "Signup configuration", description = "S04 §6. ANON or MEMBER; member block is returned only for the authenticated add-dog flow. Module-dependent fields are omitted." + PUBLIC)
    public SignupConfig config() { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/signup/identity-checks")
    @PreAuthorize("isAnonymous()")
    @SecurityRequirements
    @ContractErrors({INVALID_ID_DOCUMENT, ID_DOCUMENT_AMBIGUOUS, RATE_LIMITED})
    @Operation(summary = "Check signup identity", description = "R-04-05. ANON; 10/hour. Reveals only result and maskedEmail; recognition sends a verification link in E3-T03." + PUBLIC)
    public IdentityCheckResult identityCheck(@Valid @RequestBody IdentityCheckRequest request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/signup/towns")
    @PreAuthorize("isAnonymous()")
    @SecurityRequirements
    @ContractErrors({VALIDATION_ERROR, RATE_LIMITED})
    @Operation(summary = "Find signup towns", description = "R-04-02. ANON; 60/hour. Country-profile postal lookup; an unsupported dataset yields an empty array." + PUBLIC)
    public List<Town> towns(@RequestParam String postalCode) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/signup/upload-urls")
    @PreAuthorize("isAnonymous() or hasRole('MEMBER')")
    @SecurityRequirements
    @ContractErrors({FILE_TYPE_NOT_ALLOWED, FILE_TOO_LARGE, RATE_LIMITED})
    @Operation(summary = "Create signup upload URL", description = "R-04-08. ANON or MEMBER; 30/hour. Signed upload constrained by files.allowedTypes/files.maxSizeMb; at most 10 files per signup." + PUBLIC)
    public UploadUrl uploadUrl(@Valid @RequestBody UploadUrlRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/signup/family-group-lookups")
    @PreAuthorize("isAnonymous()")
    @RequiresModule(Module.FAMILY_GROUP)
    @SecurityRequirements
    @ContractErrors({MODULE_DISABLED, RATE_LIMITED})
    @Operation(summary = "Find family-group holder", description = "R-04-12. ANON; FAMILY_GROUP required; 20/hour. Only holderDisplayName may be disclosed." + PUBLIC)
    public FamilyGroupLookupResult familyGroup(@Valid @RequestBody FamilyGroupLookupRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/signup")
    @PreAuthorize("isAnonymous()")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({SIGNUP_CLOSED, SIGNUP_ALREADY_PENDING, MEMBER_ALREADY_EXISTS, INVALID_ID_DOCUMENT, INVALID_PHONE,
            PLAN_NOT_AVAILABLE, PAYMENT_METHOD_NOT_AVAILABLE, DOG_CHIP_ALREADY_REGISTERED, DOG_DOCUMENT_REQUIRED,
            CONSENT_VERSION_OUTDATED, FAMILY_HOLDER_NOT_FOUND, FILE_NOT_FOUND, RATE_LIMITED})
    @Operation(summary = "Submit signup", description = "S04 §6, R-04-01–24. ANON; 5/hour and 20/day. Idempotency-Key is a UUID; host-scoped anonymous replay protection is E3-T03. Body at most 64 KB and 10 files. Nonempty website honeypot will return 202 without saving. Client amounts are ignored." + PUBLIC,
            responses = {@ApiResponse(responseCode = "201", description = "Signup submitted", useReturnTypeSchema = true),
                    @ApiResponse(responseCode = "202", description = "Honeypot accepted without persistence (E3-T03)", content = @Content)})
    public SignupResult signup(@io.swagger.v3.oas.annotations.Parameter(schema = @Schema(format = "uuid")) @RequestHeader("Idempotency-Key") String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @io.swagger.v3.oas.annotations.media.ExampleObject(name = "Fictional signup", value = SIGNUP_EXAMPLE)))
            @Valid @RequestBody SignupRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/me/dogs/signup")
    @PreAuthorize("hasRole('MEMBER')")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({MEMBER_ERASED, MEMBER_NOT_ACTIVE, PLAN_NOT_AVAILABLE, PAYMENT_METHOD_NOT_AVAILABLE, DOG_CHIP_ALREADY_REGISTERED,
            DOG_DOCUMENT_REQUIRED, CONSENT_VERSION_OUTDATED, FILE_NOT_FOUND})
    @Operation(summary = "Submit an additional dog", description = "R-04-25. MEMBER; accepts valid impersonation with actor attribution. Idempotency-Key required. E3-T03 verifies member ownership and ACTIVE status; standard 501 stub.",
            responses = @ApiResponse(responseCode = "201", description = "Dog submitted", useReturnTypeSchema = true))
    public AddDogSignupResult addDog(@io.swagger.v3.oas.annotations.Parameter(schema = @Schema(format = "uuid")) @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AddDogSignupRequest request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/members/{id}/signup")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Review member signup", description = "S04 §6. ADMIN D2 aggregate with masked payment details and signed document downloads. Other-tenant resource lookup belongs to E3-T03; standard 501 stub.")
    public MemberSignupView review(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/members/{id}/validation")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_ERASED, INVALID_STATE, STALE_VERSION, LEVEL_REQUIRED, NEXT_INVOICE_DATE_REQUIRED, UPFRONT_AMOUNT_EXCEEDS_DUE,
            PLAN_NOT_AVAILABLE, MEMBERSHIP_EXISTS, FAMILY_HOLDER_NOT_FOUND})
    @Operation(summary = "Validate member signup", description = "R-04-13–16, R-04-21/22/25. ADMIN; rejects impersonation. dryRun=true returns proposals without writes; false validates using optimistic version. Standard 501 stub.",
            responses = @ApiResponse(responseCode = "200", description = "ValidationDryRun when dryRun=true; ValidationResult otherwise",
                    content = @Content(schema = @Schema(oneOf = {ValidationDryRun.class, ValidationResult.class}))))
    public Object validate(@PathVariable String id, @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody ValidationRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/members/{id}/rejection")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_ERASED, INVALID_STATE, STALE_VERSION})
    @Operation(summary = "Reject member signup", description = "R-04-23. ADMIN; reason and optimistic version required. New member becomes LEFT; an existing member stays ACTIVE and only pending dogs become INACTIVE. Standard 501 stub.")
    public RejectionResult reject(@PathVariable String id, @Valid @RequestBody RejectionRequest request) { throw new UnsupportedOperationException(); }
}
