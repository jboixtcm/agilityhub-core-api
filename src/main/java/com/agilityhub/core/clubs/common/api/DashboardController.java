package com.agilityhub.core.clubs.common.api;

import com.agilityhub.core.clubs.dashboard.application.DashboardQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.common.api.DashboardContracts.*;

@RestController
public class DashboardController {
    private final DashboardQuery query;
    private final ObjectMapper mapper;
    public DashboardController(DashboardQuery query, ObjectMapper mapper) { this.query = query; this.mapper = mapper; }
    // Valid impersonation reaches the application guard for the required IMPERSONATION_DENIED code.
    @GetMapping("/api/v1/dashboard")
    @PreAuthorize("hasRole('ADMIN') or principal.claims['imp'] == true")
    @Operation(summary = "Dashboard", description = "S14 §6, R-14-01 through R-14-07. Club-local aggregate cached per club for 60 seconds; disabled blocks are null. Impersonation is denied.",
            responses = @ApiResponse(responseCode = "200", description = "Dashboard"))
    public Dashboard dashboard() { return mapper.convertValue(query.get(LocaleContextHolder.getLocale().toLanguageTag()), Dashboard.class); }

    @GetMapping("/api/v1/dashboard/counters")
    @PreAuthorize("hasRole('ADMIN') or principal.claims['imp'] == true")
    @Operation(summary = "Dashboard counters", description = "S14 §6, R-14-08. Menu counters cached for 30 seconds; unread counts belong to the current administrator. Impersonation is denied.",
            responses = @ApiResponse(responseCode = "200", description = "DashboardCounters"))
    public DashboardCounters dashboardCounters() { return mapper.convertValue(query.counters(), DashboardCounters.class); }
}
