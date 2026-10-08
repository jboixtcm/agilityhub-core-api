package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseContractGuards;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S16 contract. All checks precede the no-write NOT_IMPLEMENTED boundary. */
@RestController
@RequiresModule(Module.COURSES)
public class ChallengesController {
    private final CourseContractGuards guards;
    public ChallengesController(CourseContractGuards guards) { this.guards = guards; }

    @GetMapping(value = "/api/v1/challenges")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "listChallenges", description = "Roles: MEMBER, INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 List<Challenge>", useReturnTypeSchema = true))
    public List<Challenge> listChallenges() {
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/challenges/{id}")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getChallenge", description = "Roles: MEMBER, INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Challenge", useReturnTypeSchema = true))
    public Challenge getChallenge(@PathVariable String id) {
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/challenges/{id}/attempts")
    @PreAuthorize("hasRole('MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "submitChallengeAttempt", description = "Roles: MEMBER. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 ChallengeAttempt", useReturnTypeSchema = true))
    public ChallengeAttempt submitChallengeAttempt(@PathVariable String id,
            @Valid @RequestBody ChallengeAttemptRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.dog(request.dogId()); guards.setupReference(request.ringSetupId());
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/me/challenge-attempts")
    @PreAuthorize("hasRole('MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "myChallengeAttempts", description = "Roles: MEMBER. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 List<ChallengeAttempt>", useReturnTypeSchema = true))
    public List<ChallengeAttempt> myChallengeAttempts() {
        throw new UnsupportedOperationException("S16 contract");
    }
}
