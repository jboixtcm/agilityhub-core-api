package com.agilityhub.core.clubs.training.api;

import com.agilityhub.core.clubs.training.application.TrainingContractAccess;
import com.agilityhub.core.clubs.training.application.TrainingSlotService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.training.api.TrainingContracts.TrainingSlots;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S09 `GET /training-slots`. INC-44 (E11-T02 question 2, ruling E132): out of {@link TrainingController}'s class-level
 * FREE_TRAINING guard because INSTRUCTOR and ADMIN keep a grid (24, D12) without the module; a member (and an impersonation
 * token, which carries MEMBER only) still gets `404 MODULE_DISABLED` from the interceptor, before any argument is read.
 */
@RestController
public class TrainingSlotsController {
    private final TrainingContractAccess access; private final TrainingSlotService slots; private final ObjectMapper mapper;
    public TrainingSlotsController(TrainingContractAccess access, TrainingSlotService slots, ObjectMapper mapper) {
        this.access = access; this.slots = slots; this.mapper = mapper;
    }

    @GetMapping("/api/v1/training-slots")
    @RequiresModule(value = Module.FREE_TRAINING, exemptRoles = {"ADMIN", "INSTRUCTOR"})
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, DOG_NOT_ACCESSIBLE})
    @Operation(summary = "trainingSlots", description = "Roles: MEMBER (also the impersonation token; interval cut to the booking window, R-09-04), INSTRUCTOR, ADMIN (at most 31 days; cells add occupants[], block and classSession). R-09-03 computed grid: holidays and days without opening hours are closed; cells BLOCKED/CLASS > BLOCKED/RING_BLOCK > BOOKED/TRAINING (OWN_TRAINING for dogId) > FREE. INC-44: INSTRUCTOR and ADMIN also get the active rings without free training (capacity 0: their cells show classes and blocks, are FREE otherwise and never count for anyFree or bookable); with FREE_TRAINING off they get every active ring that way, without training bookings, while a MEMBER gets 404 MODULE_DISABLED. Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "TrainingSlots", useReturnTypeSchema = true))
    public TrainingSlots trainingSlots(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) String dogId,
            @RequestParam(required = false) String ringId, @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        boolean staff = TrainingController.staff();
        return mapper.convertValue(slots.grid(from, to, dogId, ringId, new TrainingSlotService.Viewer(staff ? null : TrainingController.memberId(jwt), staff)),
                TrainingSlots.class);
    }
}
