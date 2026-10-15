package com.agilityhub.core.courses.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.courses.application.ports.CourseReferencePort;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * Authorization and resource lookups that still run before the S16 stubs of E9-T03/T04 (placements, setups, build sessions,
 * challenges) and before the served routes' own checks. No method writes. Course visibility is {@link CourseAccess}'s.
 */
@Service
public class CourseContractGuards {
    private final CourseAccess access;
    private final RingGeometryRepository rings;
    private final PlacementRepository placements;
    private final RingSetupRepository setups;
    private final BuildSessionRepository sessions;
    private final PlanningCatalogAccess catalogs;
    private final CourseReferencePort references;
    public CourseContractGuards(CourseAccess access, RingGeometryRepository rings, PlacementRepository placements, RingSetupRepository setups,
            BuildSessionRepository sessions, PlanningCatalogAccess catalogs, CourseReferencePort references) {
        this.access = access; this.rings = rings; this.placements = placements; this.setups = setups; this.sessions = sessions;
        this.catalogs = catalogs; this.references = references;
    }
    public static boolean role(String role) { return CourseAccess.role(role); }
    public Course course(String id, boolean write) { return access.course(id, write); }
    public RingGeometryRepository.GeometryRecord ring(String id, boolean needsGeometry) {
        var ring = rings.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND));
        if (needsGeometry && ring.geometry() == null) { throw new ApiException(NOT_FOUND); }
        return ring;
    }
    public void rings(List<String> ids) { if (ids != null) { ids.forEach(id -> ring(id, false)); } }
    public void levels(List<String> ids) {
        if (ids != null && !ids.isEmpty() && !catalogs.levelRefs().keySet().containsAll(ids)) { throw new ApiException(NOT_FOUND); }
    }
    public void activity(String id) { if (id != null && !references.activityExists(id)) { throw new ApiException(NOT_FOUND); } }
    public void dog(String id) { if (!references.dogExists(id)) { throw new ApiException(NOT_FOUND); } }
    public Placement placement(String id, boolean sheet) {
        var placement = placements.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND));
        if (sheet && placement.buildSheetFileKey() == null) { throw new ApiException(NOT_FOUND); }
        return placement;
    }
    public void placementReferences(String courseId, String ringId, String activityId, Boolean force) {
        if (Boolean.TRUE.equals(force) && !role("ADMIN")) { throw new ApiException(FORBIDDEN); }
        course(courseId, false); ring(ringId, false); activity(activityId);
    }
    public void canPublish() { if (!role("ADMIN") && !access.enabled("courses.allowInstructorPublish")) { throw new ApiException(FORBIDDEN); } }
    public void memberVisibility() { access.memberVisibility(); }
    public void setup(String id) { memberVisibility(); if (setups.findById(id).isEmpty()) { throw new ApiException(NOT_FOUND); } }
    public void setupReference(String id) { if (id != null && setups.findById(id).isEmpty()) { throw new ApiException(NOT_FOUND); } }
    public BuildSession session(String id) { return sessions.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    public void obstacle(String id, String obstacleId) {
        if (session(id).obstacles().stream().noneMatch(o -> o.obstacleId().equals(obstacleId))) { throw new ApiException(NOT_FOUND); }
    }
    public void join(String code) { if (sessions.findOpenByCode(code).isEmpty()) { throw new ApiException(NOT_FOUND); } }
    /** `{scope}` = `club` or `ring:{ringId}` of a ring of the club (the ring id, or null for the club); anything else is `400 VALIDATION_ERROR`. */
    public String inventory(String scope) {
        if (scope.equals("club")) { return null; }
        if (scope.startsWith("ring:") && scope.length() > 5) { return ring(scope.substring(5), false).id(); }
        throw new ApiException(VALIDATION_ERROR);
    }
}
