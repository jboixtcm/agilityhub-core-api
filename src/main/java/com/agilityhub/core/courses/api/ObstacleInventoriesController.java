package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseContractGuards;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S16 contract. All checks precede the no-write NOT_IMPLEMENTED boundary. */
@RestController
@RequiresModule(Module.COURSES)
public class ObstacleInventoriesController {
    private final CourseContractGuards guards;
    public ObstacleInventoriesController(CourseContractGuards guards) { this.guards = guards; }

    @GetMapping(value = "/api/v1/obstacle-inventories/{scope}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getObstacleInventory", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 ObstacleInventory", useReturnTypeSchema = true))
    public ObstacleInventory getObstacleInventory(@PathVariable String scope) {
        guards.inventory(scope);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PutMapping(value = "/api/v1/obstacle-inventories/{scope}")
    @PreAuthorize("hasRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, INVENTORY_INVALID})
    @Operation(summary = "putObstacleInventory", description = "Roles: ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 ObstacleInventory", useReturnTypeSchema = true))
    public ObstacleInventory putObstacleInventory(@PathVariable String scope,
            @Valid @RequestBody InventoryRequest request) {
        guards.inventory(scope); if (request.items().stream().anyMatch(item -> item.count() < 0)) { throw new com.agilityhub.core.shared.domain.ApiException(INVENTORY_INVALID); }
        throw new UnsupportedOperationException("S16 contract");
    }
}
