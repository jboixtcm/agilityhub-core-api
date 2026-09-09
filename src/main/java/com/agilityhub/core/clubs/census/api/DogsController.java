package com.agilityhub.core.clubs.census.api;

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
import static com.agilityhub.core.clubs.census.api.CensusResponses.*;
import static com.agilityhub.core.clubs.census.api.CensusRequests.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class DogsController {
    private final com.agilityhub.core.shared.application.lists.ListEngine lists;
    public DogsController(com.agilityhub.core.shared.application.lists.ListEngine lists) { this.lists = lists; }
    @GetMapping("/api/v1/dogs")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ListContract(filterable = {"id", "name(contains)", "breed(contains)", "levelId", "memberId", "ownerName(contains)", "handlerName(contains)", "status", "freeTrainingAllowed", "hasLicense", "licenseOrganisation", "hasPendingDocuments", "sex", "birthDate", "chip", "registeredAt", "levelAssignedAt"}, sortable = {"name", "breed", "levelOrder", "ownerLastName", "registeredAt", "levelAssignedAt"},
            columns = {"name*", "breed*", "level*#levels.enabled", "owner*", "handler", "freeTraining*@FREE_TRAINING", "licenses*", "displayStatus*", "sex", "age", "chip", "pendingDocuments", "levelAssignedAt", "pack@PACKS", "registeredAt"}, paged = true, exportable = true)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "List dogs",
            description = "R-03-22. Tenant-scoped universal list, with literal search and role/module-safe projections; fields selects a sparse response.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<DogListItem>; fields selects a sparse projection", content = @Content(schema = @Schema(implementation = DogPage.class))))
    public org.springframework.http.ResponseEntity<?> listDogs(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        return org.springframework.http.ResponseEntity.ok(lists.list("dogs", params));
    }

    @GetMapping("/api/v1/dogs/filter-values")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ListContract(filterable = {"id", "name(contains)", "breed(contains)", "levelId", "memberId", "ownerName(contains)", "handlerName(contains)", "status", "freeTrainingAllowed", "hasLicense", "licenseOrganisation", "hasPendingDocuments", "sex", "birthDate", "chip", "registeredAt", "levelAssignedAt"}, sortable = {},
            columns = {}, paged = false, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Dog filter values",
            description = "R-03-22. Top 50 facet values after q and filters on other fields; role and module restrictions apply.",
            responses = @ApiResponse(responseCode = "200", description = "FilterValues"))
    public FilterValues dogFilterValues(@RequestParam String field, @RequestParam(required = false) String q, @RequestParam(required = false) List<String> filter,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        return lists.facets("dogs", field, params);
    }

    @GetMapping("/api/v1/dogs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @Operation(summary = "Get dog",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "DogDetail"))
    public DogDetail getDog(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/dogs/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({CHIP_ALREADY_EXISTS, STALE_VERSION})
    @Operation(summary = "Update dog",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Dog"))
    public Dog updateDog(@PathVariable String id, @Valid @RequestBody DogPatch request) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/dogs/{id}/level")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({LEVELS_DISABLED, LEVEL_NOT_ACTIVE, LEVEL_UNCHANGED})
    @Operation(summary = "Update dog level",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "LevelChangeResult"))
    public LevelChangeResult updateDogLevel(@PathVariable String id, @Valid @RequestBody DogLevelRequest request) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/dogs/{id}/free-training")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FREE_TRAINING)
    @Operation(summary = "Update dog free training",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "FreeTraining"))
    public FreeTraining updateDogFreeTraining(@PathVariable String id, @Valid @RequestBody FreeTrainingRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/dogs/{id}/transfer")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({SAME_MEMBER, TARGET_MEMBER_NOT_ACTIVE, DOG_HAS_FUTURE_BOOKINGS, DOG_HAS_OPEN_PACK})
    @Operation(summary = "Transfer dog",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Dog"))
    public Dog transferDog(@PathVariable String id, @Valid @RequestBody DogTransferRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/dogs/{id}/deactivation")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DOG_HAS_FUTURE_BOOKINGS, DOG_NOT_ACTIVE, TARGET_MEMBER_NOT_ACTIVE})
    @Operation(summary = "Deactivation dog",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Dog"))
    public Dog deactivationDog(@PathVariable String id, @Valid @RequestBody ReasonRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/dogs/{id}/reactivation")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DOG_HAS_FUTURE_BOOKINGS, DOG_NOT_ACTIVE, TARGET_MEMBER_NOT_ACTIVE})
    @Operation(summary = "Reactivation dog",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Dog"))
    public Dog reactivationDog(@PathVariable String id, @Valid @RequestBody ReasonRequest request) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/dogs/{id}/photo")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED})
    @Operation(summary = "Update dog photo",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "PhotoResponse"))
    public PhotoResponse updateDogPhoto(@PathVariable String id, @Valid @RequestBody FileKeyRequest request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/dogs/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"type*", "state*", "files*"}, paged = false, exportable = false)
    @Operation(summary = "Dog documents",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "List<DogDocument>"))
    public List<DogDocument> dogDocuments(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/dogs/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DOCUMENT_TYPE_UNKNOWN, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED})
    @Operation(summary = "Upload dog document",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "DogDocument"))
    public DogDocument uploadDogDocument(@PathVariable String id, @Valid @RequestBody DogDocumentRequest request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/dogs/{id}/documents/{docId}/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Remove dog document file",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void removeDogDocumentFile(@PathVariable String id, @PathVariable String docId, @PathVariable String fileId) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/dogs/{id}/documents/reminder")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DOCUMENT_NOT_PENDING, DOCUMENT_REMINDER_TOO_SOON})
    @Operation(summary = "Remind dog document",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "202", description = "Completed without a response body", content = @Content))
    public void remindDogDocument(@PathVariable String id, @Valid @RequestBody DocumentReminderRequest request) { throw new UnsupportedOperationException(); }

}
