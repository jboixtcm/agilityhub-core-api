package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.application.ports.CourseReferencePort;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.shared.domain.ApiException;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * Resource lookups that still run before the S16 stubs (the build sheet, challenges) and the inventory scope of the served routes.
 * No method writes. Course visibility is {@link CourseAccess}'s; the placements and ring setups (E9-T03) and the build sessions
 * (E9-T04) check their own references in {@link PlacementService}, {@link RingSetupService} and {@link BuildSessionService}.
 */
@Service
public class CourseContractGuards {
    private final RingGeometryRepository rings;
    private final PlacementRepository placements;
    private final RingSetupRepository setups;
    private final CourseReferencePort references;
    public CourseContractGuards(RingGeometryRepository rings, PlacementRepository placements, RingSetupRepository setups,
            CourseReferencePort references) {
        this.rings = rings; this.placements = placements; this.setups = setups; this.references = references;
    }
    public RingGeometryRepository.GeometryRecord ring(String id, boolean needsGeometry) {
        var ring = rings.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND));
        if (needsGeometry && ring.geometry() == null) { throw new ApiException(NOT_FOUND); }
        return ring;
    }
    public void dog(String id) { if (!references.dogExists(id)) { throw new ApiException(NOT_FOUND); } }
    public Placement placement(String id, boolean sheet) {
        var placement = placements.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND));
        if (sheet && placement.buildSheetFileKey() == null) { throw new ApiException(NOT_FOUND); }
        return placement;
    }
    public void setupReference(String id) { if (id != null && setups.findById(id).isEmpty()) { throw new ApiException(NOT_FOUND); } }
    /** `{scope}` = `club` or `ring:{ringId}` of a ring of the club (the ring id, or null for the club); anything else is `400 VALIDATION_ERROR`. */
    public String inventory(String scope) {
        if (scope.equals("club")) { return null; }
        if (scope.startsWith("ring:") && scope.length() > 5) { return ring(scope.substring(5), false).id(); }
        throw new ApiException(VALIDATION_ERROR);
    }
}
