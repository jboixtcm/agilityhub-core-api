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
public class TeamController {
    @GetMapping("/api/v1/instructors")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {}, sortable = {},
            columns = {"shortName*", "color*", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List instructors",
            description = "S05 §6. includeInactive is ADMIN-only; instructor reader projection omits memberId and usage.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Instructor>"))
    public CatalogItems<Instructor> listInstructors(@RequestParam(defaultValue = "false") boolean includeInactive) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/instructors")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_NOT_ACTIVE})
    @Operation(summary = "Create instructor",
            description = "S05 §6. Creates (201) or reactivates (200) the existing profile for the same member.",
            responses = {@ApiResponse(responseCode = "201", description = "Created Instructor"),
                    @ApiResponse(responseCode = "200", description = "Reactivated Instructor", content = @Content(schema = @Schema(implementation = Instructor.class)))})
    public Instructor createInstructor(@Valid @RequestBody InstructorCreate request) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/instructors/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({INSTRUCTOR_IN_USE, STALE_VERSION})
    @Operation(summary = "Update instructor",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Instructor"))
    public Instructor updateInstructor(@PathVariable String id, @Valid @RequestBody InstructorPatch request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/instructors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({INSTRUCTOR_IN_USE})
    @Operation(summary = "Delete instructor",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteInstructor(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/administrators")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"shortName*", "since*", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List administrators",
            description = "S05 §6. includeInactive is ADMIN-only; instructor reader projection omits memberId and usage.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Administrator>"))
    public CatalogItems<Administrator> listAdministrators(@RequestParam(defaultValue = "false") boolean includeInactive) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/administrators")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_NOT_ACTIVE})
    @Operation(summary = "Create administrator",
            description = "S05 §6. Creates (201) or reactivates (200) the existing profile for the same member.",
            responses = {@ApiResponse(responseCode = "201", description = "Created Administrator"),
                    @ApiResponse(responseCode = "200", description = "Reactivated Administrator", content = @Content(schema = @Schema(implementation = Administrator.class)))})
    public Administrator createAdministrator(@Valid @RequestBody AdministratorCreate request) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/administrators/{membershipId}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({LAST_ADMIN, STALE_VERSION})
    @Operation(summary = "Update administrator",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Administrator"))
    public Administrator updateAdministrator(@PathVariable String membershipId, @Valid @RequestBody AdministratorPatch request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/administrators/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({LAST_ADMIN})
    @Operation(summary = "Delete administrator",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteAdministrator(@PathVariable String membershipId) { throw new UnsupportedOperationException(); }

}
