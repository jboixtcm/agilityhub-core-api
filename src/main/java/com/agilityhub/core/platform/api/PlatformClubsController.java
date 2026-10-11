package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.PlatformClubContractGuards;
import com.agilityhub.core.platform.application.definition.PlatformDefinitionSchema;
import com.agilityhub.core.platform.domain.Theme;
import com.agilityhub.core.shared.application.contract.*;
import com.agilityhub.core.shared.domain.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.platform.api.PlatformClubContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S17 WP-B contract only. Existing platform delegates retain their implementations. */
@RestController
public class PlatformClubsController {
    private final PlatformClubContractGuards guards;
    private final PlatformDefinitionSchema definitions;
    public PlatformClubsController(PlatformClubContractGuards guards,PlatformDefinitionSchema definitions) { this.guards=guards; this.definitions=definitions; }

    @GetMapping(value="/api/v1/platform/clubs")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, INVALID_FILTER})
    @ListContract(paged=true, filterable={"status","countryProfile","module","template"}, sortable={"name","slug"},
            fields={"id","name","slug","status","countryProfile","timeZone","modules","template","lastAdminAccessAt","usage","onboardingChecklist"},
            columns={"name*","slug*","status*","countryProfile*","timeZone*","modules*","lastAdminAccessAt*","usage*","onboardingChecklist*","template"})
    @Operation(summary="listPlatformClubs", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. Default sort name,asc; usage contains aggregates only.",
            responses=@ApiResponse(responseCode="200", description="PlatformClubPage", useReturnTypeSchema=true))
    public PlatformClubPage listPlatformClubs(@Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> query) {
        guards.list(query);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PostMapping(value="/api/v1/platform/clubs")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, SLUG_TAKEN, HOST_ALREADY_USED, MODULE_DEPENDENCY, HOST_RESERVED})
    @Operation(summary="createPlatformClub", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. ClubDefinition uses the published WP-A layout; credentials are env references only.",
            responses=@ApiResponse(responseCode="201", description="PlatformClubCreated", useReturnTypeSchema=true))
    public PlatformClubCreated createPlatformClub(@io.swagger.v3.oas.annotations.parameters.RequestBody(content=@Content(schema=@Schema(ref="#/components/schemas/ClubDefinition"))) @RequestBody Map<String,Object> request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        definitions.validate(request);
        throw new UnsupportedOperationException("S17 contract");
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND})
    @Operation(summary="getPlatformClub", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. No member data, credentials or key hashes.",
            responses=@ApiResponse(responseCode="200", description="PlatformClub", useReturnTypeSchema=true))
    public PlatformClub getPlatformClub(@PathVariable String id) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PatchMapping(value="/api/v1/platform/clubs/{id}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, STALE_VERSION, TIMEZONE_CHANGE_BLOCKED})
    @Operation(summary="patchPlatformClub", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="PlatformClub", useReturnTypeSchema=true))
    public PlatformClub patchPlatformClub(@PathVariable String id,
            @Valid @RequestBody PlatformClubPatch request) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}/definition", produces="application/yaml")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND})
    @Operation(summary="exportPlatformClub", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="String", content=@Content(mediaType="application/yaml", schema=@Schema(type="string"))))
    public String exportPlatformClub(@PathVariable String id) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/domains")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, HOST_ALREADY_USED, HOST_RESERVED})
    @Operation(summary="addPlatformDomain", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="201", description="PlatformDomain", useReturnTypeSchema=true))
    public PlatformDomain addPlatformDomain(@PathVariable String id,
            @Valid @RequestBody PlatformDomainRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @DeleteMapping(value="/api/v1/platform/clubs/{id}/domains/{host}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, NOT_FOUND})
    @Operation(summary="deletePlatformDomain", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="204", description="No content", content=@Content))
    public void deletePlatformDomain(@PathVariable String id,
            @PathVariable String host,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        guards.domain(id,host);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/domains/{host}/verification")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, NOT_FOUND, HOST_NOT_VERIFIED})
    @Operation(summary="verifyPlatformDomain", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="DomainVerification", useReturnTypeSchema=true))
    public DomainVerification verifyPlatformDomain(@PathVariable String id,
            @PathVariable String host,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        guards.domain(id,host);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/theme")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, STALE_VERSION, THEME_CONTRAST})
    @Operation(summary="updatePlatformTheme", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. Logos use uploaded file keys; the result contains display URLs.",
            responses=@ApiResponse(responseCode="200", description="Theme", useReturnTypeSchema=true))
    public Theme updatePlatformTheme(@PathVariable String id,
            @Valid @RequestBody PlatformThemeRequest request) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/modules")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, STALE_VERSION, MODULE_DEPENDENCY})
    @Operation(summary="updatePlatformModules", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="PlatformModules", useReturnTypeSchema=true))
    public PlatformModules updatePlatformModules(@PathVariable String id,
            @Valid @RequestBody PlatformModulesRequest request) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/payment-providers/{provider}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, STALE_VERSION, PROVIDER_CONFIG_INVALID})
    @Operation(summary="updatePlatformProvider", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. Provider selects the configuration variant. Secrets and IBAN are write-only; existing secrets are preserved when omitted.",
            responses=@ApiResponse(responseCode="200", description="PlatformProviderSummary", useReturnTypeSchema=true))
    public PlatformProviderSummary updatePlatformProvider(@PathVariable String id,
            @PathVariable Provider provider,
            @Valid @RequestBody PlatformProviderRequest request) {
        guards.club(id);
        boolean valid=switch(provider) {
            case STRIPE -> request.publishableKey()!=null&&!request.publishableKey().isBlank()&&request.mode()!=null;
            case SEPA_XML -> request.creditorName()!=null&&!request.creditorName().isBlank()&&request.creditorId()!=null&&!request.creditorId().isBlank()
                    &&request.iban()!=null&&!request.iban().isBlank()&&request.suffix()!=null;
            case MANUAL -> request.instructions()!=null&&!request.instructions().isEmpty();
        };
        if(!valid) throw new ApiException(PROVIDER_CONFIG_INVALID);
        throw new UnsupportedOperationException("S17 contract");
    }

    @DeleteMapping(value="/api/v1/platform/clubs/{id}/payment-providers/{provider}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, PROVIDER_IN_USE})
    @Operation(summary="deletePlatformProvider", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. Version is the current club version; returns the unconfigured provider summary.",
            responses=@ApiResponse(responseCode="200", description="PlatformProviderSummary", useReturnTypeSchema=true))
    public PlatformProviderSummary deletePlatformProvider(@PathVariable String id,
            @PathVariable Provider provider,
            @RequestParam @PositiveOrZero Long version) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/payment-providers/stripe/test")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, STRIPE_CONNECTION_FAILED})
    @Operation(summary="testPlatformStripe", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="ProviderConnectionTest", useReturnTypeSchema=true))
    public ProviderConnectionTest testPlatformStripe(@PathVariable String id) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/parameters/{key}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, STALE_VERSION, UNKNOWN_PARAMETER, PARAMETER_INVALID, MODULE_DISABLED})
    @Operation(summary="updatePlatformParameter", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="SettingsContracts.Parameter", useReturnTypeSchema=true))
    public SettingsContracts.Parameter updatePlatformParameter(@PathVariable String id,
            @PathVariable String key,
            @Valid @RequestBody SettingsContracts.ParameterUpdate request) {
        guards.club(id);
        guards.parameter(key);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/catalog-clone")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, CATALOG_NOT_EMPTY, CURRENCY_MISMATCH, TEMPLATE_CLUB_NOT_FOUND})
    @Operation(summary="clonePlatformCatalog", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. Empty destination parts only; warnings describe locale fallback.",
            responses=@ApiResponse(responseCode="200", description="CatalogCloneResult", useReturnTypeSchema=true))
    public CatalogCloneResult clonePlatformCatalog(@PathVariable String id,
            @Valid @RequestBody CatalogCloneRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        guards.template(request.fromClubId());
        throw new UnsupportedOperationException("S17 contract");
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}/admins")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND})
    @Operation(summary="listPlatformAdmins", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="List<PlatformAdmin>", useReturnTypeSchema=true))
    public List<PlatformAdmin> listPlatformAdmins(@PathVariable String id) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/admins")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, LAST_ADMIN, MEMBERSHIP_EXISTS})
    @Operation(summary="invitePlatformAdmin", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="201", description="PlatformAdmin", useReturnTypeSchema=true))
    public PlatformAdmin invitePlatformAdmin(@PathVariable String id,
            @Valid @RequestBody PlatformAdminRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @DeleteMapping(value="/api/v1/platform/clubs/{id}/admins/{accountId}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, LAST_ADMIN})
    @Operation(summary="deletePlatformAdmin", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="204", description="No content", content=@Content))
    public void deletePlatformAdmin(@PathVariable String id,
            @PathVariable String accountId,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/status")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND, INVALID_STATE})
    @Operation(summary="updatePlatformStatus", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="PlatformClub", useReturnTypeSchema=true))
    public PlatformClub updatePlatformStatus(@PathVariable String id,
            @Valid @RequestBody PlatformClubStatusRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/support-access")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND})
    @Operation(summary="startSupportAccess", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. SUPPORT grant, ADMIN role and PLATFORM_SUPPORT origin; no personal exports or full IBAN.",
            responses=@ApiResponse(responseCode="201", description="SupportAccess", useReturnTypeSchema=true))
    public SupportAccess startSupportAccess(@PathVariable String id,
            @Valid @RequestBody SupportAccessRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/public-api-key")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND})
    @Operation(summary="rotatePublicApiKey", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. One-time key response; previous key overlaps for 24 hours.",
            responses=@ApiResponse(responseCode="201", description="PublicApiKey", useReturnTypeSchema=true))
    public PublicApiKey rotatePublicApiKey(@PathVariable String id,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}/usage")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND})
    @Operation(summary="getPlatformUsage", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="PlatformClubUsage", useReturnTypeSchema=true))
    public PlatformClubUsage getPlatformUsage(@PathVariable String id,
            @RequestParam(required=false) @Pattern(regexp="[0-9]{4}-(0[1-9]|1[0-2])") String month) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}/checklist")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED, CLUB_NOT_FOUND})
    @Operation(summary="getPlatformChecklist", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED. Seven model fields; sepa means at least one configured provider when BILLING is enabled.",
            responses=@ApiResponse(responseCode="200", description="OnboardingChecklist", useReturnTypeSchema=true))
    public OnboardingChecklist getPlatformChecklist(@PathVariable String id) {
        guards.club(id);
        throw new UnsupportedOperationException("S17 contract");
    }

    @GetMapping(value="/api/v1/platform/club-templates")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_IMPLEMENTED})
    @Operation(summary="listClubTemplates", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Guards then 501 NOT_IMPLEMENTED.",
            responses=@ApiResponse(responseCode="200", description="List<PlatformClubTemplate>", useReturnTypeSchema=true))
    public List<PlatformClubTemplate> listClubTemplates() {
        throw new UnsupportedOperationException("S17 contract");
    }
}
