package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.application.ports.CoursePlacementPort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S06 «Recorregut previst» (D3/D4) and S16 R-16-13/15 (T-16-20): the placement a class or a template class plans. It needs `COURSES`
 * (`400 VALIDATION_ERROR {fieldErrors[{placementId, MODULE_DISABLED}]}` otherwise), must be a placement of the club (`404`) and of the
 * class's own ring (`422 PLACEMENT_RING_MISMATCH`, also for a class without a ring). No automatism turns it into a ring setup (0.3.7).
 */
@Component
public class ClassPlacements {
    private final CoursePlacementPort placements;
    private final PlanningContext context;
    public ClassPlacements(CoursePlacementPort placements, PlanningContext context) { this.placements = placements; this.context = context; }

    public void check(String placementId, String ringId) {
        if (placementId == null) { return; }
        if (!context.config().modules().contains(Module.COURSES)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", "placementId", "code", "MODULE_DISABLED"))));
        }
        String ring = placements.ringOf(placementId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!ring.equals(ringId)) { throw new ApiException(ErrorCode.PLACEMENT_RING_MISMATCH); }
    }
}
