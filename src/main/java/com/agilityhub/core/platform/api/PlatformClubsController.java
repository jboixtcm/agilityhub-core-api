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

/** S17 console: global platform guards, explicit target tenant and configuration-only views. */
@RestController
public class PlatformClubsController {
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.PlatformConsoleQueries queries;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.PlatformClubChanges changes;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.PlatformClubCreation creation;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.PlatformProviders providers;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.PlatformCatalogClone clones;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.ConsoleAdministratorAccess admins;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.ParameterSettingsService parameters;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.definition.ClubDefinitions exports;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.definition.ClubDefinitionCodec codec;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.ConsoleTransactions transactions;
    @org.springframework.beans.factory.annotation.Autowired private PlatformClubMapper views;
    @org.springframework.beans.factory.annotation.Autowired private SettingsMapper settings;
    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private jakarta.validation.Validator validator;
    private <T> T scoped(String id,java.util.function.Supplier<T> work) {
        guards.club(id);try(var scope=com.agilityhub.core.shared.application.TenantContext.open(id)) {return work.get();}
    }
    private final PlatformClubContractGuards guards;
    private final PlatformDefinitionSchema definitions;
    public PlatformClubsController(PlatformClubContractGuards guards,PlatformDefinitionSchema definitions) { this.guards=guards; this.definitions=definitions; }

    @GetMapping(value="/api/v1/platform/clubs")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, INVALID_FILTER})
    @ListContract(paged=true, filterable={"status","countryProfile","module","template"}, sortable={"name","slug"},
            fields={"id","name","slug","status","countryProfile","timeZone","modules","template","lastAdminAccessAt","usage","onboardingChecklist"},
            columns={"name*","slug*","status*","countryProfile*","timeZone*","modules*","lastAdminAccessAt*","usage*","onboardingChecklist*","template"})
    @Operation(summary="listPlatformClubs", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Default sort name,asc; usage contains aggregates only.",
            responses=@ApiResponse(responseCode="200", description="PlatformClubPage", content=@Content(schema=@Schema(implementation=PlatformClubPage.class))))
    public com.agilityhub.core.shared.application.contract.ApiContracts.ListPage<Map<String,Object>> listPlatformClubs(@Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> query) {
        var page=queries.list(query);
        return new com.agilityhub.core.shared.application.contract.ApiContracts.ListPage<>(page.clubs().stream().map(c->views.row(c,page.query().fields())).toList(),
                page.query().page(),page.query().size(),page.count(),(int)((page.count()+page.query().size()-1)/page.query().size()),page.query().filters());
    }

    @PostMapping(value="/api/v1/platform/clubs")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, SLUG_TAKEN, HOST_ALREADY_USED, MODULE_DEPENDENCY, HOST_RESERVED})
    @Operation(summary="createPlatformClub", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ClubDefinition uses the published WP-A layout; credentials are env references only. catalogs.fromTemplate names a template club by slug (e.g. club-template-default) or id. paymentProviders.SEPA_XML.invoiceSeriesPattern sets the billing.invoiceSeriesPattern parameter; a different value under parameters is 400 VALIDATION_ERROR {field}. Schema violations return 400 VALIDATION_ERROR with details.fieldErrors[{field, code}]; nested fields use paths such as modules[2] or paymentProviders.SEPA_XML.creditorId, an unknown property names itself (e.g. accounts), and codes are REQUIRED or INVALID_VALUE.",
            responses=@ApiResponse(responseCode="201", description="PlatformClubCreated", useReturnTypeSchema=true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public PlatformClubCreated createPlatformClub(@io.swagger.v3.oas.annotations.parameters.RequestBody(content=@Content(schema=@Schema(ref="#/components/schemas/ClubDefinition"))) @RequestBody Map<String,Object> request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        var input=creation.prepare(request);
        var source=input.path("catalogs").has("fromTemplate")?clones.template(input.path("catalogs").path("fromTemplate").asText()):null;
        if(source!=null) ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("catalogs")).remove("fromTemplate");
        String id=UUID.randomUUID().toString();
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open(id)) {
            creation.create(input,source);
            var club=views.club(queries.get(id));return new PlatformClubCreated(club,club.onboardingChecklist());
        }
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND})
    @Operation(summary="getPlatformClub", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. No member data, credentials or key hashes.",
            responses=@ApiResponse(responseCode="200", description="PlatformClub", useReturnTypeSchema=true))
    public PlatformClub getPlatformClub(@PathVariable String id) {
        return scoped(id,()->views.club(queries.get(id)));
    }

    @PatchMapping(value="/api/v1/platform/clubs/{id}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, STALE_VERSION, TIMEZONE_CHANGE_BLOCKED})
    @Operation(summary="patchPlatformClub", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="PlatformClub", useReturnTypeSchema=true))
    public PlatformClub patchPlatformClub(@PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@Content(schema=@Schema(implementation=PlatformClubPatch.class))) @RequestBody Map<String,Object> request) {
        var typed=mapper.convertValue(request,PlatformClubPatch.class);
        if(!validator.validate(typed).isEmpty()) throw new ApiException(VALIDATION_ERROR);
        return scoped(id,()->views.club(changes.patch(request).club()));
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}/definition", produces="application/yaml")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND})
    @Operation(summary="exportPlatformClub", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="String", content=@Content(mediaType="application/yaml", schema=@Schema(type="string"))))
    public String exportPlatformClub(@PathVariable String id) {
        var club=queries.get(id);var definition=exports.export(club.slug());definition.remove("accounts");
        var providerNodes=definition.withObject("paymentProviders");
        club.paymentProviders().forEach((name,value)->{
            if(!(value instanceof Map<?,?> config)) return;
            var entry=providerNodes.withObject(name);String prefix=club.slug().toUpperCase(Locale.ROOT).replace('-','_')+"_"+name+"_";
            for(String field:List.of("secretKey","webhookSecret","iban")) if(config.containsKey(field+"Enc")||config.containsKey(field))
                entry.putObject(field).put("env",prefix+field.replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase(Locale.ROOT));
            for(String field:List.of("creditorName","creditorId","suffix","mode")) if(config.get(field)!=null) entry.set(field,mapper.valueToTree(config.get(field)));
            if(name.equals("STRIPE")) entry.put("publishableKey","pk_"+Objects.toString(config.get("mode"),"test")+"_REPLACE");
        });
        return codec.write(definition);
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
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, STALE_VERSION, THEME_CONTRAST})
    @Operation(summary="updatePlatformTheme", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Logos use uploaded file keys; the result contains display URLs.",
            responses=@ApiResponse(responseCode="200", description="Theme", useReturnTypeSchema=true))
    public Theme updatePlatformTheme(@PathVariable String id,
            @Valid @RequestBody PlatformThemeRequest request) {
        return scoped(id,()->changes.themeFiles(request.version(),new com.agilityhub.core.platform.domain.Theme(null,null,null,
                request.colors(),request.fontFamily(),request.radius(),request.ringPalette(),request.mode()),request.logoFileKey(),request.logoDarkFileKey(),request.markFileKey()).club().theme());
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/modules")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, STALE_VERSION, MODULE_DEPENDENCY})
    @Operation(summary="updatePlatformModules", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="PlatformModules", useReturnTypeSchema=true))
    public PlatformModules updatePlatformModules(@PathVariable String id,
            @Valid @RequestBody PlatformModulesRequest request) {
        Set<com.agilityhub.core.platform.application.Module> selected=request.modules();
        if(selected==null) selected=request.preset()==ModulePreset.COMPLET?com.agilityhub.core.platform.application.PlatformClubCreation.allModules():
                com.agilityhub.core.platform.application.Module.defaults(com.agilityhub.core.platform.application.Module.Preset.valueOf(request.preset().name()));
        var modules=selected;return scoped(id,()->{var c=changes.modules(request.version(),modules).club();return new PlatformModules(c.modules(),c.version());});
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/payment-providers/{provider}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, STALE_VERSION, PROVIDER_CONFIG_INVALID})
    @Operation(summary="updatePlatformProvider", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Provider selects the configuration variant. Secrets and IBAN are write-only; existing secrets are preserved when omitted.",
            responses=@ApiResponse(responseCode="200", description="PlatformProviderSummary", useReturnTypeSchema=true))
    public PlatformProviderSummary updatePlatformProvider(@PathVariable String id,
            @PathVariable Provider provider,
            @Valid @RequestBody PlatformProviderRequest request) {
        Map<String,Object> input=mapper.convertValue(request,new com.fasterxml.jackson.core.type.TypeReference<>(){});
        input.put("secretKey",request.secretKey());input.put("webhookSecret",request.webhookSecret());input.put("iban",request.iban());
        // The response is read inside the write's transaction: a later concurrent write is never returned as this one's result.
        return scoped(id,()->transactions.write(()->{
            providers.update(provider.name(),input);
            return views.provider(provider.name(),queries.get(id).paymentProviders().get(provider.name()));
        }));
    }

    @DeleteMapping(value="/api/v1/platform/clubs/{id}/payment-providers/{provider}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, PROVIDER_IN_USE})
    @Operation(summary="deletePlatformProvider", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Version is the current club version; returns the unconfigured provider summary.",
            responses=@ApiResponse(responseCode="200", description="PlatformProviderSummary", useReturnTypeSchema=true))
    public PlatformProviderSummary deletePlatformProvider(@PathVariable String id,
            @PathVariable Provider provider,
            @RequestParam @PositiveOrZero Long version) {
        return scoped(id,()->transactions.write(()->{providers.remove(provider.name(),version);return views.provider(provider.name(),null);}));
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/payment-providers/stripe/test")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, STRIPE_CONNECTION_FAILED})
    @Operation(summary="testPlatformStripe", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="ProviderConnectionTest", useReturnTypeSchema=true))
    public ProviderConnectionTest testPlatformStripe(@PathVariable String id) {
        return scoped(id,()->{var result=providers.test();return new ProviderConnectionTest(result.ok(),result.accountName(),StripeMode.valueOf(result.mode().toUpperCase(Locale.ROOT)));});
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/parameters/{key}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, STALE_VERSION, UNKNOWN_PARAMETER, PARAMETER_INVALID, MODULE_DISABLED})
    @Operation(summary="updatePlatformParameter", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="SettingsContracts.Parameter", useReturnTypeSchema=true))
    public SettingsContracts.Parameter updatePlatformParameter(@PathVariable String id,
            @PathVariable String key,
            @Valid @RequestBody SettingsContracts.ParameterUpdate request) {
        return scoped(id,()->settings.parameter(parameters.updatePlatform(key,request.value(),request.scopeRef(),request.version(),request.reason())));
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/catalog-clone")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, CATALOG_NOT_EMPTY, CURRENCY_MISMATCH, TEMPLATE_CLUB_NOT_FOUND})
    @Operation(summary="clonePlatformCatalog", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Empty destination parts only; warnings describe locale fallback.",
            responses=@ApiResponse(responseCode="200", description="CatalogCloneResult", useReturnTypeSchema=true))
    public CatalogCloneResult clonePlatformCatalog(@PathVariable String id,
            @Valid @RequestBody CatalogCloneRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        guards.club(id);var source=clones.source(request.fromClubId());
        return scoped(id,()->{var result=clones.apply(source,request.parts().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
            var counts=new EnumMap<CatalogPart,Integer>(CatalogPart.class);result.copied().forEach((k,v)->counts.put(CatalogPart.valueOf(k),v));
            return new CatalogCloneResult(counts,result.warnings());});
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}/admins")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND})
    @Operation(summary="listPlatformAdmins", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="List<PlatformAdmin>", useReturnTypeSchema=true))
    public List<PlatformAdmin> listPlatformAdmins(@PathVariable String id) {
        return scoped(id,()->admins.list().stream().map(a->mapper.convertValue(a,PlatformAdmin.class)).toList());
    }

    @PostMapping(value="/api/v1/platform/clubs/{id}/admins")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, LAST_ADMIN, MEMBERSHIP_EXISTS, INVALID_STATE})
    @Operation(summary="invitePlatformAdmin", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="201", description="PlatformAdmin", useReturnTypeSchema=true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public PlatformAdmin invitePlatformAdmin(@PathVariable String id,
            @Valid @RequestBody PlatformAdminRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        return scoped(id,()->transactions.write(()->mapper.convertValue(admins.invite(request.email(),request.name()).admin(),PlatformAdmin.class)));
    }

    @DeleteMapping(value="/api/v1/platform/clubs/{id}/admins/{accountId}")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, LAST_ADMIN})
    @Operation(summary="deletePlatformAdmin", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="204", description="No content", content=@Content))
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deletePlatformAdmin(@PathVariable String id,
            @PathVariable String accountId,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        scoped(id,()->transactions.write(()->admins.remove(accountId)));
    }

    @PutMapping(value="/api/v1/platform/clubs/{id}/status")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND, INVALID_STATE})
    @Operation(summary="updatePlatformStatus", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="PlatformClub", useReturnTypeSchema=true))
    public PlatformClub updatePlatformStatus(@PathVariable String id,
            @Valid @RequestBody PlatformClubStatusRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
        return scoped(id,()->views.club(changes.status(com.agilityhub.core.platform.persistence.Club.Status.valueOf(request.status().name()),request.reason()).club()));
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
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND})
    @Operation(summary="getPlatformUsage", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="PlatformClubUsage", useReturnTypeSchema=true))
    public PlatformClubUsage getPlatformUsage(@PathVariable String id,
            @RequestParam(required=false) @Pattern(regexp="[0-9]{4}-(0[1-9]|1[0-2])") String month) {
        return views.usage(queries.get(id),month);
    }

    @GetMapping(value="/api/v1/platform/clubs/{id}/checklist")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, CLUB_NOT_FOUND})
    @Operation(summary="getPlatformChecklist", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. Seven model fields; sepa means at least one configured provider when BILLING is enabled.",
            responses=@ApiResponse(responseCode="200", description="OnboardingChecklist", useReturnTypeSchema=true))
    public OnboardingChecklist getPlatformChecklist(@PathVariable String id) {
        return views.checklist(queries.get(id));
    }

    @GetMapping(value="/api/v1/platform/club-templates")
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED})
    @Operation(summary="listClubTemplates", description="S17. Roles: AGILITYHUB_ADMIN. Global, no tenant or module required. ",
            responses=@ApiResponse(responseCode="200", description="List<PlatformClubTemplate>", useReturnTypeSchema=true))
    public List<PlatformClubTemplate> listClubTemplates() {
        return queries.templates().stream().map(c->new PlatformClubTemplate(c.id(),c.name(),c.locales(),c.currency())).toList();
    }
}
