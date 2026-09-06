package com.agilityhub.core.clubs.common.api;

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
import static com.agilityhub.core.clubs.common.api.DashboardContracts.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class DashboardController {
    @GetMapping("/api/v1/dashboard")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Dashboard",
            description = "S14 §6, R-14-01 through R-14-07. Schemas only; aggregated values and module-controlled null blocks are implemented later.",
            responses = @ApiResponse(responseCode = "200", description = "Dashboard"))
    public Dashboard dashboard() { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/dashboard/counters")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Dashboard counters",
            description = "S14 §6, R-14-08. Schemas only; menu counters are implemented later.",
            responses = @ApiResponse(responseCode = "200", description = "DashboardCounters"))
    public DashboardCounters dashboardCounters() { throw new UnsupportedOperationException(); }

}
