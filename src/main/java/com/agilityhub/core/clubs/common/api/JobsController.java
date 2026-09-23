package com.agilityhub.core.clubs.common.api;

import com.agilityhub.core.platform.application.jobs.JobContractAccess;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.platform.application.jobs.JobViews.*;
import static com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S15 WP-15-B club routes (D11 «Processos automàtics», D1 risk card). `{name}` is the R-15-01 route id.
 * Impersonation tokens are refused on every route (R-15-09). Guards run, then 501 NOT_IMPLEMENTED until E5-T05.
 */
@RestController
public class JobsController {
    static final String ADMIN = "hasRole('ADMIN') and principal.claims['imp'] != true";
    static final String NAME = "R-15-01 route id: week-opening, risk-review, no-show-notices, reminders, expirations, waitlist-fifo, payment-timeouts, class-finishing, cleanup, billing-reminder";
    private final JobContractAccess access;
    public JobsController(JobContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/jobs")
    @PreAuthorize(ADMIN)
    @ContractErrors({IMPERSONATION_DENIED})
    @Operation(summary = "jobs", description = "Roles: ADMIN (impersonation → IMPERSONATION_DENIED). One row per process with a registered implementation whose module is on (D11). Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "JobSummaries", useReturnTypeSchema = true))
    public JobSummaries jobs() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/jobs/{name}/runs")
    @PreAuthorize(ADMIN)
    @ListContract(filterable = {"status", "scheduledFor", "trigger", "dryRun"}, sortable = {"scheduledFor", "startedAt"},
            columns = {"scheduledForLocal*", "trigger*", "status*", "dryRun*", "durationMs", "counters*", "errorCount*", "skipReason"}, paged = true, exportable = false)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, JOB_UNKNOWN, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "jobRuns", description = "Roles: ADMIN. Run history of the club (universal list, CONVENCIONS_API §4); unknown name → JOB_UNKNOWN, module of the process off → MODULE_DISABLED. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<JobRunListItem>", useReturnTypeSchema = true))
    public ListPage<JobRunListItem> jobRuns(@PathVariable @Parameter(description = NAME) String name) {
        access.tenant();
        access.job(name);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/jobs/{name}/runs/{runId}")
    @PreAuthorize(ADMIN)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, JOB_UNKNOWN, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "jobRun", description = "Roles: ADMIN. Run sheet with effects.items, errors and parametersSnapshot (R-15-21). Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "JobRun", useReturnTypeSchema = true))
    public JobRunView jobRun(@PathVariable @Parameter(description = NAME) String name, @PathVariable String runId) {
        access.tenant();
        access.run(name, runId);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/jobs/{name}/trigger")
    @PreAuthorize(ADMIN)
    @ContractErrors({VALIDATION_ERROR, JOB_UNKNOWN, MODULE_DISABLED, JOB_ALREADY_RUNNING, IMPERSONATION_DENIED})
    @Operation(summary = "triggerJob", description = "Roles: ADMIN. [Simula] (dryRun true: only the plan, actions WOULD_*, nothing written but the JobRun) or [Executa ara]; synchronous, also when the switch is off; audited JOB_TRIGGERED (R-15-09). Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "JobRun", useReturnTypeSchema = true))
    public JobRunView triggerJob(@PathVariable @Parameter(description = NAME) String name, @Valid @RequestBody JobTriggerRequest request) {
        access.tenant();
        access.job(name);
        throw new UnsupportedOperationException();
    }

    @PutMapping("/api/v1/jobs/{name}/switch")
    @PreAuthorize(ADMIN)
    @ContractErrors({VALIDATION_ERROR, JOB_UNKNOWN, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "switchJob", description = "Roles: ADMIN. Writes jobs.<name>.enabled through the parameter service (ParameterChanged, audited PARAMETER_CHANGED). Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "JobSwitchResponse", useReturnTypeSchema = true))
    public JobSwitchResponse switchJob(@PathVariable @Parameter(description = NAME) String name, @Valid @RequestBody JobSwitchRequest request) {
        access.tenant();
        access.job(name);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/risk-review")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, IMPERSONATION_DENIED})
    @Operation(summary = "riskReview", description = "Roles: ADMIN, INSTRUCTOR (MEMBER → 403). S15 §6 form A for the D1 card; date defaults to today in the club zone. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "RiskReviewForm", useReturnTypeSchema = true))
    public RiskReviewContracts.RiskReview riskReview(@RequestParam(required = false) @Schema(format = "date") LocalDate date) {
        access.tenant();
        throw new UnsupportedOperationException();
    }
}
