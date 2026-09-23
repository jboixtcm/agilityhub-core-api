package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.jobs.JobContractAccess;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.platform.application.jobs.JobViews.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S15 §6 / S17 console «Processos»: global routes, AGILITYHUB_ADMIN only. Guards run, then 501 until E5-T05. */
@RestController
public class PlatformJobsController {
    static final String PLATFORM = "hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true";
    private final JobContractAccess access;
    public PlatformJobsController(JobContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/platform/jobs/overview")
    @PreAuthorize(PLATFORM)
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "platformJobsOverview", description = "Roles: AGILITYHUB_ADMIN. Club × process matrix with the last run and health OK (last success inside the period) · WARN (PARTIAL) · ALERT (FAILED or no success in more than two periods). Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.",
            responses = @ApiResponse(responseCode = "200", description = "PlatformJobsOverview", useReturnTypeSchema = true))
    public PlatformJobsOverview platformJobsOverview(@RequestParam(required = false) String clubId, @RequestParam(required = false) JobHealth status) {
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/platform/clubs/{clubId}/jobs/{name}/trigger")
    @PreAuthorize(PLATFORM)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, JOB_UNKNOWN, MODULE_DISABLED, JOB_ALREADY_RUNNING})
    @Operation(summary = "platformTriggerJob", description = "Roles: AGILITYHUB_ADMIN. Same execution as POST /jobs/{name}/trigger inside the club scope; audited JOB_TRIGGERED. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards.",
            responses = @ApiResponse(responseCode = "200", description = "JobRun", useReturnTypeSchema = true))
    public JobRunView platformTriggerJob(@PathVariable String clubId, @PathVariable @Parameter(description = "R-15-01 route id") String name,
            @Valid @RequestBody JobTriggerRequest request) {
        access.platformJob(clubId, name);
        throw new UnsupportedOperationException();
    }
}
