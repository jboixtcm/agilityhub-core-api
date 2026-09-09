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
import static com.agilityhub.core.clubs.catalogs.domain.CatalogKind.*;

/** S05 tenant catalogs and club offers. */
@RestController
public class CatalogsController {
    private final com.agilityhub.core.clubs.catalogs.application.CatalogService catalogs;
    private final CatalogViews views;
    private final OfferViews offers;
    private final com.agilityhub.core.clubs.catalogs.application.PlanService plans;
    public CatalogsController(com.agilityhub.core.clubs.catalogs.application.CatalogService catalogs, CatalogViews views, OfferViews offers, com.agilityhub.core.clubs.catalogs.application.PlanService plans) {
        this.catalogs = catalogs; this.views = views; this.offers = offers; this.plans = plans;
    }
    @GetMapping("/api/v1/levels")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"code*", "name*", "color*", "capacity*", "grantsFreeTraining@FREE_TRAINING", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List levels",
            description = "S05 §6. Unpaginated catalog; includeInactive is ADMIN-only. MEMBER/INSTRUCTOR receive LevelReaderView without usage or translation maps.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Level>"))
    public CatalogItems<Level> listLevels(@RequestParam(defaultValue = "false") boolean includeInactive) { return views.levels(includeInactive); }

    @PostMapping("/api/v1/levels")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, DUPLICATE_NAME})
    @Operation(summary = "Create level",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "Level"))
    public Level createLevel(@Valid @RequestBody LevelCreate request) { return views.level(views.create(LEVEL, request), false); }

    @GetMapping("/api/v1/levels/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Get level",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Level"))
    public Level getLevel(@PathVariable String id) { return views.level(id, false); }

    @PatchMapping("/api/v1/levels/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({STALE_VERSION, LEVEL_IN_USE, DUPLICATE_NAME})
    @Operation(summary = "Update level",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Level"))
    public Level updateLevel(@PathVariable String id, @Valid @RequestBody LevelPatch request) { views.update(LEVEL, id, request); return views.level(id, Boolean.FALSE.equals(request.active())); }

    @DeleteMapping("/api/v1/levels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({LEVEL_IN_USE})
    @Operation(summary = "Delete level",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteLevel(@PathVariable String id) { catalogs.delete(LEVEL, id); }

    @PutMapping("/api/v1/levels/order")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ORDER_INCOMPLETE})
    @Operation(summary = "Order levels",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Level>"))
    public CatalogItems<Level> orderLevels(@Valid @RequestBody LevelOrder request) { catalogs.order(LEVEL, request.levelIds()); return views.levels(true); }

    @GetMapping("/api/v1/rings")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"name*", "shortName*", "color*", "effectiveTrainingCapacity@FREE_TRAINING", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List rings",
            description = "S05 §6. Unpaginated catalog; includeInactive is ADMIN-only. MEMBER/INSTRUCTOR receive RingReaderView without usage or translation maps.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Ring>"))
    public CatalogItems<Ring> listRings(@RequestParam(defaultValue = "false") boolean includeInactive) { return views.rings(includeInactive); }

    @PostMapping("/api/v1/rings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, DUPLICATE_NAME})
    @Operation(summary = "Create ring",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "Ring"))
    public Ring createRing(@Valid @RequestBody RingCreate request) { return views.ring(views.create(RING, request)); }

    @GetMapping("/api/v1/rings/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Get ring",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Ring"))
    public Ring getRing(@PathVariable String id) { return views.ring(id); }

    @PatchMapping("/api/v1/rings/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({STALE_VERSION, RING_IN_USE, DUPLICATE_NAME})
    @Operation(summary = "Update ring",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Ring"))
    public Ring updateRing(@PathVariable String id, @Valid @RequestBody RingPatch request) { views.update(RING, id, request); return views.ring(id); }

    @DeleteMapping("/api/v1/rings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({RING_IN_USE})
    @Operation(summary = "Delete ring",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteRing(@PathVariable String id) { catalogs.delete(RING, id); }

    @PutMapping("/api/v1/rings/order")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ORDER_INCOMPLETE})
    @Operation(summary = "Order rings",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Ring>"))
    public CatalogItems<Ring> orderRings(@Valid @RequestBody RingOrder request) { catalogs.order(RING, request.ringIds()); return views.rings(true); }

    @GetMapping("/api/v1/plans")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"name*", "type*", "currentPrices@BILLING", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List plans",
            description = "S05 §6. Unpaginated catalog; includeInactive is ADMIN-only. MEMBER/INSTRUCTOR receive PlanReaderView without usage or translation maps.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Plan>"))
    public CatalogItems<Plan> listPlans(@RequestParam(defaultValue = "false") boolean includeInactive) { return offers.plans(includeInactive); }

    @PostMapping("/api/v1/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, DUPLICATE_NAME})
    @Operation(summary = "Create plan",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "Plan"))
    public Plan createPlan(@Valid @RequestBody PlanCreate request) { return offers.plan(plans.create(offers.input(request)), true, false); }

    @GetMapping("/api/v1/plans/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Get plan",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Plan"))
    public Plan getPlan(@PathVariable String id) { return offers.plan(id, true, false); }

    @PatchMapping("/api/v1/plans/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({STALE_VERSION, PLAN_IN_USE, DUPLICATE_NAME})
    @Operation(summary = "Update plan",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Plan"))
    public Plan updatePlan(@PathVariable String id, @Valid @RequestBody PlanPatch request) { plans.update(id, offers.input(request)); return offers.plan(id, true, Boolean.FALSE.equals(request.active())); }

    @DeleteMapping("/api/v1/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PLAN_IN_USE})
    @Operation(summary = "Delete plan",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deletePlan(@PathVariable String id) { plans.delete(id); }

    @PutMapping("/api/v1/plans/order")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ORDER_INCOMPLETE})
    @Operation(summary = "Order plans",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Plan>"))
    public CatalogItems<Plan> orderPlans(@Valid @RequestBody PlanOrder request) { plans.order(request.planIds()); return offers.plans(true); }

    @GetMapping("/api/v1/faq-entries")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @RequiresModule(Module.FAQ)
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"category*", "question*", "answer*", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List faqs",
            description = "S05 §6. Unpaginated catalog; includeInactive is ADMIN-only. MEMBER/INSTRUCTOR receive FaqReaderView without usage or translation maps.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<FaqEntry>"))
    public CatalogItems<FaqEntry> listFaqs(@RequestParam(defaultValue = "false") boolean includeInactive) { return views.faqs(includeInactive); }

    @PostMapping("/api/v1/faq-entries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "Create faq",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "FaqEntry"))
    public FaqEntry createFaq(@Valid @RequestBody FaqCreate request) { return views.faq(views.create(FAQ, request)); }

    @PatchMapping("/api/v1/faq-entries/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ContractErrors({STALE_VERSION})
    @Operation(summary = "Update faq",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "FaqEntry"))
    public FaqEntry updateFaq(@PathVariable String id, @Valid @RequestBody FaqPatch request) { views.update(FAQ, id, request); return views.faq(id); }

    @DeleteMapping("/api/v1/faq-entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ContractErrors({STALE_VERSION})
    @Operation(summary = "Delete faq",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteFaq(@PathVariable String id) { catalogs.delete(FAQ, id); }

    @PutMapping("/api/v1/faq-entries/order")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ContractErrors({ORDER_INCOMPLETE})
    @Operation(summary = "Order faqs",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<FaqEntry>"))
    public CatalogItems<FaqEntry> orderFaqs(@Valid @RequestBody FaqOrder request) { catalogs.order(FAQ, request.faqEntryIds()); return views.faqs(true); }

    @GetMapping("/api/v1/faq-entries/filter-values")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAQ)
    @ListContract(filterable = {"category"}, sortable = {},
            columns = {}, paged = false, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Faq category values",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "FilterValues"))
    public FilterValues faqCategoryValues(@RequestParam @Schema(allowableValues = "category") String field, @RequestParam(required = false) String q, @RequestParam(required = false) List<String> filter, jakarta.servlet.http.HttpServletRequest servletRequest) {
        // Preserve commas inside a single filter value; Spring's List conversion splits them otherwise.
        String[] rawFilters = servletRequest.getParameterValues("filter");
        return views.categories(field, q, rawFilters == null ? null : java.util.Arrays.asList(rawFilters));
    }

}
