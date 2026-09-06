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
public class FamilyGroupsController {
    @PostMapping("/api/v1/family-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAMILY_GROUP)
    @ContractErrors({FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP, FAMILY_GROUP_TOO_SMALL})
    @Operation(summary = "Create family group",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "FamilyGroup"))
    public FamilyGroup createFamilyGroup(@Valid @RequestBody FamilyGroupRequest request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/family-groups/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAMILY_GROUP)
    @Operation(summary = "Get family group",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "FamilyGroup"))
    public FamilyGroup getFamilyGroup(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/family-groups/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAMILY_GROUP)
    @ContractErrors({FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP, FAMILY_GROUP_TOO_SMALL})
    @Operation(summary = "Update family group",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "FamilyGroup"))
    public FamilyGroup updateFamilyGroup(@PathVariable String id, @Valid @RequestBody FamilyGroupUpdate request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/family-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAMILY_GROUP)
    @Operation(summary = "Dissolve family group",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void dissolveFamilyGroup(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/me/family-group")
    @PreAuthorize("hasRole('MEMBER')")
    @RequiresModule(Module.FAMILY_GROUP)
    @Operation(summary = "My family group",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = {@ApiResponse(responseCode = "200", description = "MeFamilyGroup"),
                    @ApiResponse(responseCode = "204", description = "No family group", content = @Content)})
    public MeFamilyGroup myFamilyGroup() { throw new UnsupportedOperationException(); }

}
