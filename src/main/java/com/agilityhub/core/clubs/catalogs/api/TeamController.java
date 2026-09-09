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

/** S05 team profiles, backed by the shared role assignment use case. */
@RestController
public class TeamController {
    private final com.agilityhub.core.clubs.catalogs.application.RoleAssignmentService team;
    private final TeamViews views;
    public TeamController(com.agilityhub.core.clubs.catalogs.application.RoleAssignmentService team, TeamViews views) {
        this.team = team; this.views = views;
    }
    @GetMapping("/api/v1/instructors")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ListContract(filterable = {}, sortable = {},
            columns = {"shortName*", "color*", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List instructors",
            description = "S05 §6. includeInactive is ADMIN-only; instructor reader projection omits memberId and usage.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Instructor>"))
    public CatalogItems<Instructor> listInstructors(@RequestParam(defaultValue = "false") boolean includeInactive) { return views.instructors(includeInactive); }

    @PostMapping("/api/v1/instructors")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_NOT_ACTIVE})
    @Operation(summary = "Create instructor",
            description = "S05 §6. Creates (201) or reactivates (200) the existing profile for the same member.",
            responses = {@ApiResponse(responseCode = "201", description = "Created Instructor"),
                    @ApiResponse(responseCode = "200", description = "Reactivated Instructor", content = @Content(schema = @Schema(implementation = Instructor.class)))})
    public org.springframework.http.ResponseEntity<Instructor> createInstructor(@Valid @RequestBody InstructorCreate request) {
        var result = team.createInstructor(request.memberId(), request.shortName(), request.color());
        return org.springframework.http.ResponseEntity.status(result.created() ? 201 : 200).body(views.instructor(result.id()));
    }

    @PatchMapping("/api/v1/instructors/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({INSTRUCTOR_IN_USE, STALE_VERSION})
    @Operation(summary = "Update instructor",
            description = "S05 team mutation. Tenant comes from the JWT; ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Instructor"))
    public Instructor updateInstructor(@PathVariable String id, @Valid @RequestBody InstructorPatch request) {
        team.updateInstructor(id, request.shortName(), request.color(), request.active(), request.version()); return views.instructor(id);
    }

    @DeleteMapping("/api/v1/instructors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({INSTRUCTOR_IN_USE})
    @Operation(summary = "Delete instructor",
            description = "S05 team mutation. Tenant comes from the JWT; ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteInstructor(@PathVariable String id) { team.deleteInstructor(id); }

    @GetMapping("/api/v1/administrators")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"shortName*", "since*", "active*"}, paged = false, exportable = false)
    @Operation(summary = "List administrators",
            description = "S05 §6. includeInactive is ADMIN-only; instructor reader projection omits memberId and usage.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Administrator>"))
    public CatalogItems<Administrator> listAdministrators(@RequestParam(defaultValue = "false") boolean includeInactive) { return views.administrators(includeInactive); }

    @PostMapping("/api/v1/administrators")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_NOT_ACTIVE})
    @Operation(summary = "Create administrator",
            description = "S05 §6. Creates (201) or reactivates (200) the existing profile for the same member.",
            responses = {@ApiResponse(responseCode = "201", description = "Created Administrator"),
                    @ApiResponse(responseCode = "200", description = "Reactivated Administrator", content = @Content(schema = @Schema(implementation = Administrator.class)))})
    public org.springframework.http.ResponseEntity<Administrator> createAdministrator(@Valid @RequestBody AdministratorCreate request) {
        var result = team.createAdministrator(request.memberId(), request.shortName(), request.since());
        return org.springframework.http.ResponseEntity.status(result.created() ? 201 : 200).body(views.administrator(result.id()));
    }

    @PatchMapping("/api/v1/administrators/{membershipId}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({LAST_ADMIN, STALE_VERSION})
    @Operation(summary = "Update administrator",
            description = "S05 team mutation. Tenant comes from the JWT; ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Administrator"))
    public Administrator updateAdministrator(@PathVariable String membershipId, @Valid @RequestBody AdministratorPatch request) {
        team.updateAdministrator(membershipId, request.shortName(), request.since(), request.active(), request.version()); return views.administrator(membershipId);
    }

    @DeleteMapping("/api/v1/administrators/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({LAST_ADMIN})
    @Operation(summary = "Delete administrator",
            description = "S05 team mutation. Tenant comes from the JWT; ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteAdministrator(@PathVariable String membershipId) { team.deleteAdministrator(membershipId); }

}
