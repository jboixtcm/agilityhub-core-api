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
public class MyCensusController {
    @GetMapping("/api/v1/me/dogs")
    @PreAuthorize("hasRole('MEMBER')")
    @ListContract(filterable = {}, sortable = {},
            columns = {"name*", "breed*", "level#levels.enabled", "photoUrl", "documents", "licenses", "freeTrainingAllowed@FREE_TRAINING"}, paged = false, exportable = false)
    @Operation(summary = "My dogs",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "MeDogs"))
    public MeDogs myDogs() { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/me/dogs/{id}/photo")
    @PreAuthorize("hasRole('MEMBER')")
    @ContractErrors({FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED})
    @Operation(summary = "Update my dog photo",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "PhotoResponse"))
    public PhotoResponse updateMyDogPhoto(@PathVariable String id, @Valid @RequestBody FileKeyRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/me/dogs/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MEMBER')")
    @ContractErrors({DOCUMENT_TYPE_UNKNOWN, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED})
    @Operation(summary = "Upload my dog document",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "201", description = "DogDocument"))
    public DogDocument uploadMyDogDocument(@PathVariable String id, @Valid @RequestBody DogDocumentRequest request) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/me/dogs/{id}/instructor-note")
    @PreAuthorize("hasRole('MEMBER')")
    @RequiresModule(Module.TASKS)
    @Operation(summary = "Update instructor note",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "InstructorNote"))
    public InstructorNote updateInstructorNote(@PathVariable String id, @Valid @RequestBody InstructorNoteRequest request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/me/profile")
    @PreAuthorize("hasRole('MEMBER')")
    @Operation(summary = "My census profile",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "MeProfile"))
    public MeProfile myCensusProfile() { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/me/profile")
    @PreAuthorize("hasRole('MEMBER')")
    @ContractErrors({VALIDATION_ERROR, STALE_VERSION, READ_ONLY})
    @Operation(summary = "Update my census profile",
            description = "S03 §6, R-03-09. Contact emails, phones and address only; identity and payment fields are read-only. Distinct from S01 PUT /me/profile (active role).",
            responses = @ApiResponse(responseCode = "200", description = "MeProfile"))
    public MeProfile updateMyCensusProfile(@Valid @RequestBody MeProfilePatch request) { throw new UnsupportedOperationException(); }

}
