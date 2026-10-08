package com.agilityhub.core.courses.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.courses.application.ports.CourseReferencePort;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** Authorization and resource lookups run before every S16 stub. No method writes. */
@Service
public class CourseContractGuards {
    private final CourseRepository courses;
    private final GlobalCourseRepository global;
    private final RingGeometryRepository rings;
    private final PlacementRepository placements;
    private final RingSetupRepository setups;
    private final BuildSessionRepository sessions;
    private final PlanningCatalogAccess catalogs;
    private final CourseReferencePort references;
    private final ClubConfigService configs;
    public CourseContractGuards(CourseRepository courses, GlobalCourseRepository global, RingGeometryRepository rings,
            PlacementRepository placements, RingSetupRepository setups, BuildSessionRepository sessions,
            PlanningCatalogAccess catalogs, CourseReferencePort references, ClubConfigService configs) {
        this.courses = courses; this.global = global; this.rings = rings; this.placements = placements; this.setups = setups;
        this.sessions = sessions; this.catalogs = catalogs; this.references = references; this.configs = configs;
    }
    public static boolean role(String role) {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
    private boolean staff() { return role("ADMIN") || role("INSTRUCTOR"); }
    public Course course(String id, boolean write) {
        Course course = courses.findById(id).filter(c -> c.deletedAt() == null).or(() -> global.findById(id)).orElseThrow(() -> new ApiException(NOT_FOUND));
        boolean author = java.util.Objects.equals(course.ownerAccountId(), CurrentUser.current().accountId());
        boolean visible = course.visibility() == Visibility.PUBLIC || course.visibility() == Visibility.PRIVATE && author
                || course.visibility() == Visibility.CLUB && staff() && TenantContext.require().equals(course.clubId());
        if (!visible) { throw new ApiException(COURSE_NOT_VISIBLE); }
        if (write && (course.ownerType() == OwnerType.AGILITYHUB || course.ownerType() == OwnerType.ACCOUNT && !author)) { throw new ApiException(FORBIDDEN); }
        return course;
    }
    public void platformCourse(String id) {
        if (global.findById(id).filter(c -> c.ownerType() == OwnerType.AGILITYHUB).isEmpty()) { throw new ApiException(NOT_FOUND); }
    }
    public void ring(String id, boolean needsGeometry) {
        var ring = rings.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND));
        if (needsGeometry && ring.geometry() == null) { throw new ApiException(NOT_FOUND); }
    }
    public void rings(List<String> ids) { if (ids != null) { ids.forEach(id -> ring(id, false)); } }
    public void levels(List<String> ids) {
        if (ids != null && !ids.isEmpty() && !catalogs.levelRefs().keySet().containsAll(ids)) { throw new ApiException(NOT_FOUND); }
    }
    /** S16 §3: levelIds name club levels; a tenantless library request cannot reference any club's level. */
    public void platformLevels(List<String> ids) {
        if (ids != null && !ids.isEmpty()) { throw new ApiException(NOT_FOUND); }
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
    public void canPublish() { if (!role("ADMIN") && !enabled("courses.allowInstructorPublish")) { throw new ApiException(FORBIDDEN); } }
    public void memberVisibility() { if (!staff() && !enabled("courses.showSetupToMembers")) { throw new ApiException(NOT_FOUND); } }
    /** R-16-16: the club's `files.allowedTypes` (a `type/*` entry matches its family) and `files.maxSizeMb`; per-purpose MIME rules are E9-T02's. */
    public void upload(String contentType, long size) {
        var config = configs.get(TenantContext.require());
        boolean allowed = config.get("files.allowedTypes", List.class).stream().anyMatch(raw -> {
            String item = raw.toString(); return item.endsWith("/*") ? contentType.startsWith(item.substring(0, item.length() - 1)) : contentType.equals(item);
        });
        if (!allowed) { throw new ApiException(FILE_TYPE_NOT_ALLOWED); }
        int max = config.get("files.maxSizeMb", Integer.class);
        if (size <= 0 || size > max * 1024L * 1024) { throw new ApiException(FILE_TOO_LARGE, java.util.Map.of("maxSizeMb", max)); }
    }
    private boolean enabled(String key) { return Boolean.TRUE.equals(configs.get(TenantContext.require()).get(key, Boolean.class)); }
    public void setup(String id) { memberVisibility(); if (setups.findById(id).isEmpty()) { throw new ApiException(NOT_FOUND); } }
    public void setupReference(String id) { if (id != null && setups.findById(id).isEmpty()) { throw new ApiException(NOT_FOUND); } }
    public BuildSession session(String id) { return sessions.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    public void obstacle(String id, String obstacleId) {
        if (session(id).obstacles().stream().noneMatch(o -> o.obstacleId().equals(obstacleId))) { throw new ApiException(NOT_FOUND); }
    }
    public void join(String code) { if (sessions.findOpenByCode(code).isEmpty()) { throw new ApiException(NOT_FOUND); } }
    public void inventory(String scope) {
        if (scope.equals("club")) { return; }
        if (scope.startsWith("ring:") && scope.length() > 5) { ring(scope.substring(5), false); return; }
        throw new ApiException(VALIDATION_ERROR);
    }
}
