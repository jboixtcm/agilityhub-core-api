package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.application.*;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;
import static com.agilityhub.core.clubs.census.application.CensusValues.object;

@RestController
@PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
public class AdminCensusActionsController {
    public record MergeMember(String id, String fullName, Integer memberNumber, long version) { }
    public record MemberMergePreview(MergeMember source, MergeMember target, Map<String,Long> counts, List<String> blockers) { }
    public record MemberMergeRequest(@NotBlank String targetMemberId, @NotNull @Min(0) Long sourceVersion, @NotNull @Min(0) Long targetVersion) { }
    private final DogService dogs; private final CensusQuery queries; private final MemberMergeService merges; private final IdentityTransactions transactions;
    public AdminCensusActionsController(DogService dogs, CensusQuery queries, MemberMergeService merges, IdentityTransactions transactions) {
        this.dogs = dogs; this.queries = queries; this.merges = merges; this.transactions = transactions;
    }
    @PostMapping("/api/v1/members/{id}/dogs")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MEMBER_ERASED, TARGET_MEMBER_NOT_ACTIVE, CHIP_ALREADY_EXISTS, STALE_VERSION, INVALID_STATE, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "Add an active dog to an existing member", description = "ADMIN only, impersonation refused. Creates an ACTIVE dog linked to the ACTIVE member, preserving their plan and account. No signup approval or automatic payment; fees remain managed through billing. Tenant from the JWT. Idempotency-Key prevents duplicate creation.",
            responses = @ApiResponse(responseCode = "201", description = "Active dog", content = @Content(schema = @Schema(implementation = CensusResponses.Dog.class))))
    public Map<String,Object> addDog(@PathVariable String id, @Valid @RequestBody CensusRequests.AdminDogCreate request,
            @RequestHeader("Idempotency-Key") UUID key) {
        return transactions.run(() -> {
            var values = object("name", request.name(), "breed", request.breed(), "sex", request.sex().name(), "birthDate", request.birthDate().toString(),
                    "chip", request.chip(), "handlerName", request.handlerName(), "handlerIsMember", request.handlerIsMember(), "levelId", request.levelId(), "freeTrainingAllowed", request.freeTrainingAllowed());
            return queries.dog(dogs.createActive(id, values));
        });
    }
    @GetMapping("/api/v1/members/{id}/merge-preview")
    @ContractErrors({NOT_FOUND, MEMBER_ERASED, INVALID_STATE})
    @Operation(summary = "Preview a member merge", description = "ADMIN only, impersonation refused. Read-only preview of the source, surviving target, operational references and blocking conflicts. Historical source records remain linked and readable after the merge. Tenant from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "Preview", content = @Content(schema = @Schema(implementation = MemberMergePreview.class))))
    public Map<String,Object> preview(@PathVariable String id, @RequestParam String targetMemberId) { return merges.preview(id, targetMemberId); }

    @PostMapping("/api/v1/members/{id}/merge")
    @ContractErrors({NOT_FOUND, MEMBER_ERASED, INVALID_STATE, STALE_VERSION, VALIDATION_ERROR, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "Merge duplicate members preserving history", description = "ADMIN only, impersonation refused. The path identifies the duplicate source; targetMemberId survives. Both preview versions are required. Moves dogs and operational references atomically, retains historical source records and links, leaves the source inactive and unbillable. Conflicts abort every change. Idempotency-Key required. Tenant from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "Applied preview", content = @Content(schema = @Schema(implementation = MemberMergePreview.class))))
    public Map<String,Object> merge(@PathVariable String id, @Valid @RequestBody MemberMergeRequest request, @RequestHeader("Idempotency-Key") UUID key) {
        return transactions.run(() -> merges.merge(id, request.targetMemberId(), request.sourceVersion(), request.targetVersion()));
    }
}
