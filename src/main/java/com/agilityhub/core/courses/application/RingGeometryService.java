package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.domain.CourseEvent;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.RingGeometryRules;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * R-16-04 (and R-16-06's trigger): a ring's geometry, embedded in `rings` but owned by S16 and written field by field
 * ({@link RingGeometryRepository}). Saving checks {@link RingGeometryRules} (`422 GEOMETRY_INVALID {violations[]}`) and the
 * geometry's own `version` (`409 STALE_VERSION`), increments it, marks the ring's placements `stale`, publishes
 * `RingGeometryChanged{ringId, version}` and writes `RING_GEOMETRY_CHANGED`, all in one transaction. Removing it is refused while a
 * placement or an active setup with a placement uses it (`409 RING_GEOMETRY_IN_USE`). The first geometry of a club also creates the
 * club's venue (S16 §14.2: one club, one venue). §14.2's calibrations are kept beside the geometry.
 */
@Service
public class RingGeometryService {
    public record GeometryInput(double lengthM, double widthM, double borderClearanceM, Double orientationDeg, Surface surface, String notes,
            List<RingDoor> doors, List<RingNoGoZone> noGoZones, List<RingMarker> markers) { }
    public record CalibrationInput(CalibrationOutcome outcome, double averageErrorCm, double maxErrorCm, int markerCount, String deviceModel,
            String osVersion, String appVersion, String arProvider, String notes, JsonNode rawMetadata) { }
    static final String ENTITY = "RingGeometry";
    private final RingGeometryRepository rings;
    private final PlacementRepository placements;
    private final RingSetupRepository setups;
    private final CalibrationLogRepository calibrations;
    private final VenueRepository venues;
    private final ClubConfigService configs;
    private final EventPublisher events;
    private final Clock clock;
    public RingGeometryService(RingGeometryRepository rings, PlacementRepository placements, RingSetupRepository setups,
            CalibrationLogRepository calibrations, VenueRepository venues, ClubConfigService configs, EventPublisher events, Clock clock) {
        this.rings = rings; this.placements = placements; this.setups = setups; this.calibrations = calibrations; this.venues = venues;
        this.configs = configs; this.events = events; this.clock = clock;
    }

    private RingGeometryRepository.GeometryRecord ring(String id) { return rings.findById(id).orElseThrow(() -> new ApiException(NOT_FOUND)); }
    /** A ring without a geometry is `404 NOT_FOUND` (D16: «Aquesta pista no té geometria»). */
    public RingGeometry get(String id) {
        var geometry = ring(id).geometry();
        if (geometry == null) { throw new ApiException(NOT_FOUND); }
        return geometry;
    }

    @Transactional
    @Audited(action = AuditAction.RING_GEOMETRY_CHANGED, entityType = "'" + ENTITY + "'", entity = "#id")
    public RingGeometry put(String id, GeometryInput in, long version) {
        var ring = ring(id);
        var violations = RingGeometryRules.check(in.lengthM(), in.widthM(), in.borderClearanceM(), in.doors(), in.noGoZones(), in.markers());
        if (!violations.isEmpty()) {
            throw new ApiException(GEOMETRY_INVALID, Map.of("violations", violations.stream()
                    .map(v -> Map.of("path", v.path(), "rule", v.rule().name())).toList()));
        }
        long current = ring.geometry() == null ? 0 : ring.geometry().version();
        if (version != current) { throw new ApiException(STALE_VERSION); }
        var now = clock.instant();
        var geometry = new RingGeometry(in.lengthM(), in.widthM(), in.borderClearanceM(), in.orientationDeg(), in.surface(), in.notes(),
                List.copyOf(in.doors()), List.copyOf(in.noGoZones()), List.copyOf(in.markers()), current + 1, now);
        rings.write(id, geometry, current);
        placements.markStale(id);
        ensureVenue();
        events.publish(new CourseEvent(CourseEvent.Kind.RingGeometryChanged, TenantContext.require(), id, now,
                Map.of("ringId", id, "version", geometry.version()), CourseAccess.accountId(), null, DomainEvent.Origin.BACKOFFICE));
        return geometry;
    }

    @Transactional
    @Audited(action = AuditAction.RING_GEOMETRY_CHANGED, entityType = "'" + ENTITY + "'", entity = "#id")
    public void delete(String id, long version) {
        var geometry = get(id);
        if (geometry.version() != version) { throw new ApiException(STALE_VERSION); }
        if (placements.usesRing(id) || setups.activeWithPlacement(id)) { throw new ApiException(RING_GEOMETRY_IN_USE); }
        rings.clear(id, version);
    }

    /**
     * `RING_GEOMETRY_CHANGED`'s before and after: the stored geometry, or null without one. A bean of its own: the audit aspect
     * loads it, so it cannot itself be a bean the aspect advises.
     */
    @org.springframework.stereotype.Component
    static class Audit implements com.agilityhub.core.platform.application.audit.AuditableLoader {
        private final RingGeometryRepository rings;
        private final com.fasterxml.jackson.databind.ObjectMapper mapper;
        Audit(RingGeometryRepository rings, com.fasterxml.jackson.databind.ObjectMapper mapper) { this.rings = rings; this.mapper = mapper; }
        @Override public String entityType() { return ENTITY; }
        /** The geometry as its wire map (`AuditDiff` compares maps and values), so the entry lists each changed field. */
        @Override public Object load(String id) {
            return rings.findById(id).map(RingGeometryRepository.GeometryRecord::geometry).map(geometry -> mapper.convertValue(geometry, Map.class)).orElse(null);
        }
    }

    /** S16 §14.2: the club's venue, created with the club's name and slug the first time the club draws a ring. */
    private void ensureVenue() {
        var club = configs.get(TenantContext.require()).club();
        var now = clock.instant(); String actor = CourseAccess.accountId();
        venues.ensure(new Venue(UUID.randomUUID().toString(), club.id(), club.slug(), club.name(), null, club.city(), null, null, IndoorOutdoor.MIXED,
                null, null, null, VenueVisibility.MEMBERS_ONLY, PartnerStatus.INACTIVE, null, null, PaperSize.A4, now, actor, now, actor));
    }

    @Transactional
    public CalibrationLog calibrate(String ringId, CalibrationInput in) {
        ring(ringId);
        var now = clock.instant(); String actor = CourseAccess.accountId();
        return calibrations.insert(new CalibrationLog(UUID.randomUUID().toString(), TenantContext.require(), ringId, in.outcome(), in.averageErrorCm(),
                in.maxErrorCm(), in.markerCount(), in.deviceModel(), in.osVersion(), in.appVersion(), in.arProvider(), in.notes(), in.rawMetadata(),
                actor, now, now, actor));
    }
    public List<CalibrationLog> calibrations(String ringId) { ring(ringId); return calibrations.forRing(ringId); }
}
