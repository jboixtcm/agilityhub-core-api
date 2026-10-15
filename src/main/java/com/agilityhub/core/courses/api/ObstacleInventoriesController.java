package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.ObstacleInventoryService;
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

/** S16 R-16-12 (E9-T02): the club's and the rings' obstacle inventories. */
@RestController
@RequiresModule(Module.COURSES)
public class ObstacleInventoriesController {
    private final ObstacleInventoryService inventories;
    public ObstacleInventoriesController(ObstacleInventoryService inventories) { this.inventories = inventories; }

    @GetMapping(value = "/api/v1/obstacle-inventories/{scope}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @Operation(summary = "getObstacleInventory", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. scope = club or ring:{ringId} (else VALIDATION_ERROR). "
            + "R-16-12: ring:{ringId} answers the ring's effective inventory, its own (scope RING) or else the club's (scope CLUB); without one → NOT_FOUND.",
            responses = @ApiResponse(responseCode = "200", description = "S16 ObstacleInventory", useReturnTypeSchema = true))
    public ObstacleInventory getObstacleInventory(@PathVariable String scope) {
        return CourseViews.inventory(inventories.get(scope));
    }

    @PutMapping(value = "/api/v1/obstacle-inventories/{scope}")
    @PreAuthorize("hasRole('ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, INVENTORY_INVALID})
    @Operation(summary = "putObstacleInventory", description = "Roles: ADMIN. Module: COURSES. Replaces the club's (club) or the ring's own (ring:{ringId}) inventory; "
            + "every count ≥ 0 and one item per type, else INVENTORY_INVALID. Last write wins. Publishes InventoryChanged{scope, ringId}.",
            responses = @ApiResponse(responseCode = "200", description = "S16 ObstacleInventory", useReturnTypeSchema = true))
    public ObstacleInventory putObstacleInventory(@PathVariable String scope,
            @Valid @RequestBody InventoryRequest request) {
        return CourseViews.inventory(inventories.put(scope, request.items()));
    }
}
