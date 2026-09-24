package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.jobs.JobAdminService;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.platform.application.jobs.JobViews.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S15 §6 / S17 console «Processos»: global routes, AGILITYHUB_ADMIN only. */
@RestController
public class PlatformJobsController {
    static final String PLATFORM = "hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true";
    private final JobAdminService jobs;
    public PlatformJobsController(JobAdminService jobs) { this.jobs = jobs; }

    @GetMapping("/api/v1/platform/jobs/overview")
    @PreAuthorize(PLATFORM)
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "platformJobsOverview", description = "Roles: AGILITYHUB_ADMIN. Club × process matrix with the last run and health OK (last success inside the period) · WARN (PARTIAL) · ALERT (FAILED or no success in more than two periods). Only processes with an implementation and their module on; status keeps the cells of that health.",
            responses = @ApiResponse(responseCode = "200", description = "PlatformJobsOverview", useReturnTypeSchema = true))
    public PlatformJobsOverview platformJobsOverview(@RequestParam(required = false) String clubId, @RequestParam(required = false) JobHealth status) {
        return jobs.overview(clubId, status);
    }

    @PostMapping("/api/v1/platform/clubs/{clubId}/jobs/{name}/trigger")
    @PreAuthorize(PLATFORM)
    @ContractErrors(value = {VALIDATION_ERROR, NOT_FOUND, JOB_UNKNOWN, MODULE_DISABLED, JOB_ALREADY_RUNNING}, omit = 422)
    @Operation(summary = "platformTriggerJob", description = "Roles: AGILITYHUB_ADMIN. Same execution as POST /jobs/{name}/trigger inside the club scope; audited JOB_TRIGGERED.",
            responses = @ApiResponse(responseCode = "200", description = "JobRun", useReturnTypeSchema = true))
    public JobRunView platformTriggerJob(@PathVariable String clubId, @PathVariable @Parameter(description = "R-15-01 route id") String name,
            @Valid @RequestBody JobTriggerRequest request) {
        return jobs.platformTrigger(clubId, name, request.dryRun());
    }
}
