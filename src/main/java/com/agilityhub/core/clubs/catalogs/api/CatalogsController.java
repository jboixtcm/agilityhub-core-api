package com.agilityhub.core.clubs.catalogs.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;
import static com.agilityhub.core.clubs.catalogs.api.CatalogResponses.*;
import static com.agilityhub.core.clubs.catalogs.api.CatalogRequests.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class CatalogsController {
    @GetMapping("/api/v1/levels")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"code*", "name*", "color*", "capacity*", "grantsFreeTraining@FREE_TRAINING", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List levels",
            description = "S05 §6. Unpaginated catalog; includeInactive is ADMIN-only. MEMBER/INSTRUCTOR receive LevelReaderView without usage or translation maps.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Level>"))
    public CatalogItems<Level> listLevels(@RequestParam(defaultValue = "false") boolean includeInactive) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/levels")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, DUPLICATE_NAME})
    @Operation(summary = "Create level",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "Level"))
    public Level createLevel(@Valid @RequestBody LevelCreate request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/levels/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Get level",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Level"))
    public Level getLevel(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/levels/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({STALE_VERSION, LEVEL_IN_USE, DUPLICATE_NAME})
    @Operation(summary = "Update level",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Level"))
    public Level updateLevel(@PathVariable String id, @Valid @RequestBody LevelPatch request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/levels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({LEVEL_IN_USE})
    @Operation(summary = "Delete level",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteLevel(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/levels/order")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ORDER_INCOMPLETE})
    @Operation(summary = "Order levels",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Level>"))
    public CatalogItems<Level> orderLevels(@Valid @RequestBody LevelOrder request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/rings")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"name*", "shortName*", "color*", "effectiveTrainingCapacity@FREE_TRAINING", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List rings",
            description = "S05 §6. Unpaginated catalog; includeInactive is ADMIN-only. MEMBER/INSTRUCTOR receive RingReaderView without usage or translation maps.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Ring>"))
    public CatalogItems<Ring> listRings(@RequestParam(defaultValue = "false") boolean includeInactive) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/rings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, DUPLICATE_NAME})
    @Operation(summary = "Create ring",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "Ring"))
    public Ring createRing(@Valid @RequestBody RingCreate request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/rings/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Get ring",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Ring"))
    public Ring getRing(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/rings/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({STALE_VERSION, RING_IN_USE, DUPLICATE_NAME})
    @Operation(summary = "Update ring",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Ring"))
    public Ring updateRing(@PathVariable String id, @Valid @RequestBody RingPatch request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/rings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({RING_IN_USE})
    @Operation(summary = "Delete ring",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteRing(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/rings/order")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ORDER_INCOMPLETE})
    @Operation(summary = "Order rings",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Ring>"))
    public CatalogItems<Ring> orderRings(@Valid @RequestBody RingOrder request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/plans")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"name*", "type*", "currentPrices@BILLING", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List plans",
            description = "S05 §6. Unpaginated catalog; includeInactive is ADMIN-only. MEMBER/INSTRUCTOR receive PlanReaderView without usage or translation maps.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Plan>"))
    public CatalogItems<Plan> listPlans(@RequestParam(defaultValue = "false") boolean includeInactive) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, DUPLICATE_NAME})
    @Operation(summary = "Create plan",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "Plan"))
    public Plan createPlan(@Valid @RequestBody PlanCreate request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/plans/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Get plan",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Plan"))
    public Plan getPlan(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/plans/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({STALE_VERSION, PLAN_IN_USE, DUPLICATE_NAME})
    @Operation(summary = "Update plan",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Plan"))
    public Plan updatePlan(@PathVariable String id, @Valid @RequestBody PlanPatch request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PLAN_IN_USE})
    @Operation(summary = "Delete plan",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deletePlan(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/plans/order")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ORDER_INCOMPLETE})
    @Operation(summary = "Order plans",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Plan>"))
    public CatalogItems<Plan> orderPlans(@Valid @RequestBody PlanOrder request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/faq-entries")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @RequiresModule(Module.FAQ)
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"category*", "question*", "answer*", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List faqs",
            description = "S05 §6. Unpaginated catalog; includeInactive is ADMIN-only. MEMBER/INSTRUCTOR receive FaqReaderView without usage or translation maps.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<FaqEntry>"))
    public CatalogItems<FaqEntry> listFaqs(@RequestParam(defaultValue = "false") boolean includeInactive) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/faq-entries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "Create faq",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "FaqEntry"))
    public FaqEntry createFaq(@Valid @RequestBody FaqCreate request) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/faq-entries/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ContractErrors({STALE_VERSION, STALE_VERSION})
    @Operation(summary = "Update faq",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "FaqEntry"))
    public FaqEntry updateFaq(@PathVariable String id, @Valid @RequestBody FaqPatch request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/faq-entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ContractErrors({STALE_VERSION})
    @Operation(summary = "Delete faq",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteFaq(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/faq-entries/order")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ContractErrors({ORDER_INCOMPLETE})
    @Operation(summary = "Order faqs",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<FaqEntry>"))
    public CatalogItems<FaqEntry> orderFaqs(@Valid @RequestBody FaqOrder request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/faq-entries/filter-values")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ListContract(filterable = {"category"}, sortable = {},
            columns = {}, paged = false, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Faq category values",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "FilterValues"))
    public FilterValues faqCategoryValues(@RequestParam @Schema(allowableValues = "category") String field, @RequestParam(required = false) String q, @RequestParam(required = false) List<String> filter) { throw new UnsupportedOperationException(); }

}
