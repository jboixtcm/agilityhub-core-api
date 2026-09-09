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
public class FamilyGroupsController {
    private final com.agilityhub.core.clubs.census.application.CensusQuery queries;
    private final com.agilityhub.core.clubs.census.application.CensusAccess access;
    private final com.agilityhub.core.identity.application.IdentityTransactions transactions;
    private final com.agilityhub.core.clubs.census.application.FamilyGroupService groups;
    public FamilyGroupsController(com.agilityhub.core.clubs.census.application.CensusQuery queries, com.agilityhub.core.clubs.census.application.CensusAccess access,
            com.agilityhub.core.identity.application.IdentityTransactions transactions, com.agilityhub.core.clubs.census.application.FamilyGroupService groups) {
        this.queries = queries; this.access = access; this.transactions = transactions; this.groups = groups;
    }
    @PostMapping("/api/v1/family-groups")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAMILY_GROUP)
    @ContractErrors({FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP, FAMILY_GROUP_TOO_SMALL, MEMBER_ERASED})
    @Operation(summary = "Create family group",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "201", description = "FamilyGroup", content = @Content(schema = @Schema(implementation = FamilyGroup.class))))
    public java.util.Map<String,Object> createFamilyGroup(@Valid @RequestBody FamilyGroupRequest request) { return transactions.run(() -> queries.family(groups.create(request.holderMemberId(), request.memberIds()))); }

    @GetMapping("/api/v1/family-groups/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAMILY_GROUP)
    @Operation(summary = "Get family group",
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = @ApiResponse(responseCode = "200", description = "FamilyGroup", content = @Content(schema = @Schema(implementation = FamilyGroup.class))))
    public java.util.Map<String,Object> getFamilyGroup(@PathVariable String id) { return queries.family(id); }

    @PutMapping("/api/v1/family-groups/{id}")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAMILY_GROUP)
    @ContractErrors({FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP, FAMILY_GROUP_TOO_SMALL, MEMBER_ERASED})
    @Operation(summary = "Update family group",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "FamilyGroup", content = @Content(schema = @Schema(implementation = FamilyGroup.class))))
    public java.util.Map<String,Object> updateFamilyGroup(@PathVariable String id, @Valid @RequestBody FamilyGroupUpdate request) { return transactions.run(() -> { groups.update(id, request.holderMemberId(), request.memberIds(), request.version()); return queries.family(id); }); }

    @DeleteMapping("/api/v1/family-groups/{id}")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.FAMILY_GROUP)
    @Operation(summary = "Dissolve family group",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void dissolveFamilyGroup(@PathVariable String id) { transactions.run(() -> { groups.dissolve(id); return null; }); }

    @GetMapping("/api/v1/me/family-group")
    @PreAuthorize("hasRole('MEMBER')")
    @RequiresModule(Module.FAMILY_GROUP)
    @Operation(summary = "My family group",
            description = "Tenant-scoped S03 response with role and ownership checks.",
            responses = {@ApiResponse(responseCode = "200", description = "MeFamilyGroup", content = @Content(schema = @Schema(implementation = MeFamilyGroup.class))),
                    @ApiResponse(responseCode = "204", description = "No family group", content = @Content)})
    public org.springframework.http.ResponseEntity<?> myFamilyGroup() { var group = queries.myFamily(); return group == null ? org.springframework.http.ResponseEntity.noContent().build() : org.springframework.http.ResponseEntity.ok(group); }

}
