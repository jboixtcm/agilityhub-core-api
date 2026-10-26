package com.agilityhub.core.migration.api;

import com.agilityhub.core.migration.application.MigrationReports;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
public class MigrationReportsController {
    private final MigrationReports reports;
    public MigrationReportsController(MigrationReports reports) { this.reports=reports; }
    @GetMapping("/api/v1/platform/clubs/{clubId}/migrations/{runId}/report")
    @PreAuthorize("(hasRole('AGILITYHUB_ADMIN') or hasRole('ADMIN')) and principal.claims['imp'] != true")
    @SupportRestricted
    @ContractErrors({FORBIDDEN,IMPERSONATION_DENIED,SUPPORT_RESTRICTED,NOT_FOUND,EXPORT_EXPIRED})
    @Operation(summary="getMigrationReport",description="S18 R-18-15. Roles: AGILITYHUB_ADMIN or ADMIN of the explicit club. Production report.xlsx only; signed private URL valid for at most five minutes and never past the run's 30-day expiry. Missing/unpublished report is NOT_FOUND; expired report is EXPORT_EXPIRED. Support and impersonation are refused.",
            responses=@ApiResponse(responseCode="200",description="MigrationReportLink",useReturnTypeSchema=true))
    public MigrationReports.ReportLink getMigrationReport(@PathVariable String clubId,@PathVariable String runId,JwtAuthenticationToken token) {
        if (token.getAuthorities().stream().noneMatch(authority->"ROLE_AGILITYHUB_ADMIN".equals(authority.getAuthority()))
                && !clubId.equals(token.getToken().getClaimAsString("clubId"))) { throw new ApiException(FORBIDDEN); }
        try (var scope=TenantContext.open(clubId)) { return reports.download(runId); }
    }
}
