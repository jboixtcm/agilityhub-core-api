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

/** S03 tenant-scoped census endpoints. */
@RestController
public class DogsController {
    private final com.agilityhub.core.clubs.census.application.CensusQuery queries;
    private final com.agilityhub.core.clubs.census.application.CensusAccess access;
    private final com.agilityhub.core.identity.application.IdentityTransactions transactions;
    private final com.agilityhub.core.shared.application.lists.ListEngine lists;
    private final com.agilityhub.core.clubs.census.application.DogService dogs;
    private final com.agilityhub.core.clubs.census.application.DogTransferService transfers;
    private final com.agilityhub.core.clubs.census.application.DocumentService documents;
    public DogsController(com.agilityhub.core.shared.application.lists.ListEngine lists,
            com.agilityhub.core.clubs.census.application.CensusQuery queries, com.agilityhub.core.clubs.census.application.CensusAccess access,
            com.agilityhub.core.identity.application.IdentityTransactions transactions, com.agilityhub.core.clubs.census.application.DogService dogs,
            com.agilityhub.core.clubs.census.application.DogTransferService transfers, com.agilityhub.core.clubs.census.application.DocumentService documents) {
        this.lists = lists; this.queries = queries; this.access = access; this.transactions = transactions; this.dogs = dogs; this.transfers = transfers; this.documents = documents;
    }
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
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = @ApiResponse(responseCode = "200", description = "DogDetail", content = @Content(schema = @Schema(implementation = DogDetail.class))))
    public java.util.Map<String,Object> getDog(@PathVariable String id) { return queries.detail(id); }

    @PatchMapping("/api/v1/dogs/{id}")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({CHIP_ALREADY_EXISTS, STALE_VERSION, MEMBER_ERASED})
    @Operation(summary = "Update dog",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "Dog", content = @Content(schema = @Schema(implementation = Dog.class))))
    public java.util.Map<String,Object> updateDog(@PathVariable String id, @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = DogPatch.class))) @RequestBody java.util.Map<String,Object> request) { return transactions.run(() -> { if ("PENDING".equals(queries.dog(id).get("status"))) { dogs.patchPending(id, request); } else { dogs.patch(id, request); } return queries.dog(id); }); }

    @PatchMapping("/api/v1/dogs/{id}/level")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({LEVELS_DISABLED, LEVEL_NOT_ACTIVE, LEVEL_UNCHANGED, MEMBER_ERASED})
    @Operation(summary = "Update dog level",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "LevelChangeResult", content = @Content(schema = @Schema(implementation = LevelChangeResult.class))))
    public java.util.Map<String,Object> updateDogLevel(@PathVariable String id, @Valid @RequestBody DogLevelRequest request) { return transactions.run(() -> { dogs.level(id, request.levelId()); return com.agilityhub.core.clubs.census.application.CensusValues.object("level", queries.detail(id).get("level"), "levelAssignedAt", queries.dog(id).get("levelAssignedAt")); }); }

    @PatchMapping("/api/v1/dogs/{id}/free-training")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FREE_TRAINING)
    @Operation(summary = "Update dog free training",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "FreeTraining", content = @Content(schema = @Schema(implementation = FreeTraining.class))))
    public java.util.Map<String,Object> updateDogFreeTraining(@PathVariable String id, @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = FreeTrainingRequest.class))) @RequestBody java.util.Map<String,Object> request) { com.agilityhub.core.clubs.census.application.CensusValues.allow(request, java.util.Set.of("override")); if (!request.containsKey("override") || (request.get("override") != null && !(request.get("override") instanceof Boolean))) { throw com.agilityhub.core.clubs.census.application.CensusValues.invalid("override", "REQUIRED"); }
        return transactions.run(() -> { dogs.free(id, (Boolean) request.get("override")); return queries.free(id); }); }

    @PostMapping("/api/v1/dogs/{id}/transfer")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({SAME_MEMBER, TARGET_MEMBER_NOT_ACTIVE, DOG_HAS_FUTURE_BOOKINGS, DOG_HAS_OPEN_PACK, MEMBER_ERASED})
    @Operation(summary = "Transfer dog",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "Dog", content = @Content(schema = @Schema(implementation = Dog.class))))
    public java.util.Map<String,Object> transferDog(@PathVariable String id, @Valid @RequestBody DogTransferRequest request) { return transactions.run(() -> { transfers.transfer(id, request.toMemberId(), request.reason()); return queries.dog(id); }); }

    @PostMapping("/api/v1/dogs/{id}/deactivation")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DOG_HAS_FUTURE_BOOKINGS, DOG_NOT_ACTIVE, TARGET_MEMBER_NOT_ACTIVE, MEMBER_ERASED})
    @Operation(summary = "Deactivation dog",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "Dog", content = @Content(schema = @Schema(implementation = Dog.class))))
    public java.util.Map<String,Object> deactivationDog(@PathVariable String id, @Valid @RequestBody ReasonRequest request) { return transactions.run(() -> { dogs.deactivate(id, request.reason()); return queries.dog(id); }); }

    @PostMapping("/api/v1/dogs/{id}/reactivation")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DOG_HAS_FUTURE_BOOKINGS, DOG_NOT_ACTIVE, TARGET_MEMBER_NOT_ACTIVE, MEMBER_ERASED})
    @Operation(summary = "Reactivation dog",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "Dog", content = @Content(schema = @Schema(implementation = Dog.class))))
    public java.util.Map<String,Object> reactivationDog(@PathVariable String id, @Valid @RequestBody ReasonRequest request) { return transactions.run(() -> { dogs.reactivate(id); return queries.dog(id); }); }

    @PutMapping("/api/v1/dogs/{id}/photo")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, MEMBER_ERASED})
    @Operation(summary = "Update dog photo",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "PhotoResponse"))
    public PhotoResponse updateDogPhoto(@PathVariable String id, @Valid @RequestBody FileKeyRequest request) { return new PhotoResponse(transactions.run(() -> documents.photo(id, request.fileKey(), false))); }

    @GetMapping("/api/v1/dogs/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"type*", "state*", "files*"}, paged = false, exportable = false)
    @Operation(summary = "Dog documents",
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = @ApiResponse(responseCode = "200", description = "List<DogDocument>", content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = DogDocument.class)))))
    public java.util.List<java.util.Map<String,Object>> dogDocuments(@PathVariable String id) { return documents.list(id); }

    @PostMapping("/api/v1/dogs/{id}/documents")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DOCUMENT_TYPE_UNKNOWN, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, MEMBER_ERASED})
    @Operation(summary = "Upload dog document",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "201", description = "DogDocument", content = @Content(schema = @Schema(implementation = DogDocument.class))))
    public java.util.Map<String,Object> uploadDogDocument(@PathVariable String id, @Valid @RequestBody DogDocumentRequest request) { return transactions.run(() -> documents.upload(id, request.type(), request.name(), request.fileKey(), false)); }

    @DeleteMapping("/api/v1/dogs/{id}/documents/{docId}/files/{fileId}")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Remove dog document file",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void removeDogDocumentFile(@PathVariable String id, @PathVariable String docId, @PathVariable String fileId) { transactions.run(() -> { documents.remove(id, docId, fileId); return null; }); }

    @PostMapping("/api/v1/dogs/{id}/documents/reminder")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DOCUMENT_NOT_PENDING, DOCUMENT_REMINDER_TOO_SOON, MEMBER_ERASED})
    @Operation(summary = "Remind dog document",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "202", description = "Completed without a response body", content = @Content))
    public void remindDogDocument(@PathVariable String id, @Valid @RequestBody DocumentReminderRequest request) { transactions.run(() -> { documents.remind(id, request.type()); return null; }); }

}
