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
public class MyCensusController {
    private final com.agilityhub.core.clubs.census.application.CensusQuery queries;
    private final com.agilityhub.core.clubs.census.application.CensusAccess access;
    private final com.agilityhub.core.identity.application.IdentityTransactions transactions;
    private final com.agilityhub.core.clubs.census.application.MemberService members;
    private final com.agilityhub.core.clubs.census.application.DogService dogs;
    private final com.agilityhub.core.clubs.census.application.DocumentService documents;
    public MyCensusController(com.agilityhub.core.clubs.census.application.CensusQuery queries, com.agilityhub.core.clubs.census.application.CensusAccess access,
            com.agilityhub.core.identity.application.IdentityTransactions transactions, com.agilityhub.core.clubs.census.application.MemberService members,
            com.agilityhub.core.clubs.census.application.DogService dogs, com.agilityhub.core.clubs.census.application.DocumentService documents) {
        this.queries = queries; this.access = access; this.transactions = transactions; this.members = members; this.dogs = dogs; this.documents = documents;
    }
    @GetMapping("/api/v1/me/dogs")
    @PreAuthorize("hasRole('MEMBER')")
    @ListContract(filterable = {}, sortable = {},
            columns = {"name*", "breed*", "level#levels.enabled", "photoUrl", "documents", "licenses", "freeTrainingAllowed@FREE_TRAINING"}, paged = false, exportable = false)
    @Operation(summary = "My dogs",
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = @ApiResponse(responseCode = "200", description = "MeDogs", content = @Content(schema = @Schema(implementation = MeDogs.class))))
    public java.util.Map<String,Object> myDogs() { return queries.myDogs(); }

    @PutMapping("/api/v1/me/dogs/{id}/photo")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('MEMBER')")
    @ContractErrors({FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, MEMBER_ERASED})
    @Operation(summary = "Update my dog photo",
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = @ApiResponse(responseCode = "200", description = "PhotoResponse"))
    public PhotoResponse updateMyDogPhoto(@PathVariable String id, @Valid @RequestBody FileKeyRequest request) { return new PhotoResponse(transactions.run(() -> documents.photo(id, request.fileKey(), true))); }

    @PostMapping("/api/v1/me/dogs/{id}/documents")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MEMBER')")
    @ContractErrors({DOCUMENT_TYPE_UNKNOWN, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, MEMBER_ERASED})
    @Operation(summary = "Upload my dog document",
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = @ApiResponse(responseCode = "201", description = "DogDocument", content = @Content(schema = @Schema(implementation = DogDocument.class))))
    public java.util.Map<String,Object> uploadMyDogDocument(@PathVariable String id, @Valid @RequestBody DogDocumentRequest request) { return transactions.run(() -> documents.upload(id, request.type(), request.name(), request.fileKey(), true)); }

    @PutMapping("/api/v1/me/dogs/{id}/instructor-note")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('MEMBER')")
    @RequiresModule(Module.TASKS)
    @Operation(summary = "Update instructor note",
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = @ApiResponse(responseCode = "200", description = "InstructorNote", content = @Content(schema = @Schema(implementation = InstructorNote.class))))
    public Object updateInstructorNote(@PathVariable String id, @Valid @RequestBody InstructorNoteRequest request) { return transactions.run(() -> { dogs.note(id, request.text()); return queries.dog(id).get("instructorNote"); }); }

    @GetMapping("/api/v1/me/profile")
    @PreAuthorize("hasRole('MEMBER')")
    @Operation(summary = "My census profile",
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = @ApiResponse(responseCode = "200", description = "MeProfile", content = @Content(schema = @Schema(implementation = MeProfile.class))))
    public java.util.Map<String,Object> myCensusProfile() { return queries.myProfile(); }

    @PatchMapping("/api/v1/me/profile")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('MEMBER')")
    @ContractErrors({VALIDATION_ERROR, STALE_VERSION, READ_ONLY, MEMBER_ERASED})
    @Operation(summary = "Update my census profile",
            description = "S03 §6, R-03-09. Contact emails, phones and address only; identity and payment fields are read-only. Distinct from S01 PUT /me/profile (active role).",
            responses = @ApiResponse(responseCode = "200", description = "MeProfile", content = @Content(schema = @Schema(implementation = MeProfile.class))))
    public java.util.Map<String,Object> updateMyCensusProfile(@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = MeProfilePatch.class))) @RequestBody java.util.Map<String,Object> request) { return transactions.run(() -> { members.patch(access.me().id, request, true); return queries.myProfile(); }); }

}
