package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.domain.CourseParts.RingGeometry;
import com.agilityhub.core.courses.persistence.BuildSession;
import com.agilityhub.core.courses.persistence.Course;
import com.agilityhub.core.courses.persistence.Placement;
import com.agilityhub.core.courses.persistence.Venue;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * S16 §14.1 / E77 (A9): `BuildSessionExportV1` maps the core's documents back to the planner's spellings byte for byte, derives the
 * columns the core does not keep, and passes the committed schema; the schema's six markers are why the route refuses other rings.
 */
class BuildSessionExportTest {
    static final Instant NOW = Instant.parse("2026-10-08T09:30:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final CourseSchemas schemas;
    BuildSessionExportTest() throws Exception { schemas = new CourseSchemas(mapper); }

    ObjectNode read(String json) throws Exception { return (ObjectNode) mapper.readTree(json.replace('\'', '"')); }
    ObjectNode courseData() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/courses/course-data-valid.json")) { return (ObjectNode) mapper.readTree(input); }
    }
    BuildSession session() throws Exception {
        return mapper.treeToValue(read("""
                {'id':'session-a','clubId':'club-a','placementId':'placement-a','ringId':'ring-a','status':'IN_PROGRESS','selectedStrategy':'CLOSER_OBSTACLE_ORDER',
                 'live':true,'startedByAccountId':'account-a','startedAt':'2026-10-08T08:00:00Z','finishedAt':null,'lastKnownMarkerId':null,'localCacheVersion':0,
                 'progressJson':null,'joinCode':'ABC234','joinToken':'fictional-token','joinTokenRotatedAt':'2026-10-08T08:00:00Z',
                 'obstacles':[{'obstacleId':'obstacle-a','status':'PLACED','byAccountId':'account-a','at':'2026-10-08T08:05:00Z','lastUpdatedClientSessionId':null},
                              {'obstacleId':'obstacle-b','status':'NEEDS_CHECK','byAccountId':null,'at':null,'lastUpdatedClientSessionId':null}],
                 'participants':[],'buildSheetFileKey':null,'version':3}"""), BuildSession.class);
    }
    Placement placement(String mode) throws Exception {
        return mapper.treeToValue(read("""
                {'id':'placement-a','clubId':'club-a','courseId':'course-a','courseVersion':0,'ringId':'ring-a',
                 'transform':{'offsetXM':2,'offsetYM':1,'rotationDeg':90,'flipX':true,'flipY':false},'placementMode':'FIT_TO_RING','warningThresholdM':1.5,
                 'resolvedObstacles':[],'warningsJson':[{'id':'w-1','ruleId':'border-clearance','severity':'warning','message':'Example','obstacleSourceId':'1',
                 'courseNoGoZoneId':null,'ringNoGoZoneId':null,'doorId':null,'distanceMeters':0.8,'thresholdMeters':1}],'stale':false,'status':'READY_TO_BUILD',
                 'mode':'%s','scheduledAt':'2026-10-10T08:00:00Z','grades':['1'],'sizes':['S','L'],'displayName':'Central 1','name':null,'activityId':'activity-a',
                 'classSessionIds':[],'buildSheetFileKey':null,'createdByAccountId':'account-a','createdAt':'2026-10-08T08:00:00Z','updatedAt':'2026-10-08T08:00:00Z',
                 'version':1}""".formatted(mode)), Placement.class);
    }
    Course course(String discipline) throws Exception {
        var json = read("""
                {'id':'course-a','ownerType':'CLUB','clubId':'club-a','visibility':'CLUB','name':{'ca':'Recorregut del club'},'discipline':'%s','levelIds':[],
                 'tags':[],'source':'SMARTER','schemaVersion':1,'version':0,'history':[]}""".formatted(discipline));
        json.set("normalizedJson", courseData());
        return mapper.treeToValue(json, Course.class);
    }
    Venue venue() throws Exception {
        return mapper.treeToValue(read("""
                {'id':'venue-a','clubId':'club-a','slug':'fictional-club','name':'Fictional Club','country':null,'city':'Example town','surface':'SAND',
                 'indoorOutdoor':'MIXED','visibility':'MEMBERS_ONLY','partnerStatus':'ACTIVE','defaultPaperSize':'A4'}"""), Venue.class);
    }
    RingGeometry geometry(int markers) throws Exception {
        var json = read("""
                {'lengthM':40,'widthM':22,'borderClearanceM':1,'orientationDeg':null,'surface':'GRASS','notes':null,
                 'doors':[{'id':'door-n','label':'N','side':'NORTH','startM':3,'endM':5,'flow':'IN','clearanceM':1.5,'polygonPointsM':[{'x':3,'y':22},{'x':5,'y':22}],
                           'isActive':true,'notes':null}],
                 'noGoZones':[{'id':'bassa','label':'Bassa','polygonPointsM':[{'x':10,'y':10},{'x':13,'y':10},{'x':13,'y':13}],'warningMarginM':0.5}],
                 'markers':[],'version':2,'updatedAt':'2026-10-08T08:00:00Z'}""");
        var list = (ArrayNode) json.path("markers");
        for (int i = 0; i < markers; i++) {
            list.add(read("{'id':'m-%d','label':'%s','markerUid':'uid-%d','role':'ANCHOR','xM':%d,'yM':%d,'zM':0,'rotationYDeg':0,'physicalWidthM':0.2,"
                    .formatted(i, "ABCDEF".charAt(i), i, i * 8, i % 2 * 22) + "'physicalHeightM':0.2,'isFixed':true,'isActive':true,'aprilTagId':%d,'notes':null}".formatted(i)));
        }
        return mapper.treeToValue(json, RingGeometry.class);
    }
    ObjectNode export(String mode, String discipline, int markers) throws Exception {
        return BuildSessionExport.build(mapper, session(), placement(mode), course(discipline), venue(),
                new BuildSessionExport.Ring("ring-a", "Central", geometry(markers)), NOW);
    }

    @Test void T_16_03_theExportUsesThePlannersSpellingsAndPassesTheCommittedSchema() throws Exception {
        var export = export("TRAINING", "JUMPING", 6);
        try { schemas.validateExport(export); } catch (ApiException failed) { throw new AssertionError(failed.getCause().getMessage(), failed); }
        assertThat(export.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(export.path("exportedAt").asText()).isEqualTo("2026-10-08T09:30:00Z");
        assertThat(export.path("producer").asText()).isEqualTo("agilityhub-core-api");
        var session = export.path("buildSession");
        assertThat(session.path("status").asText()).isEqualTo("in_progress");
        assertThat(session.path("selected_strategy").asText()).isEqualTo("closer_obstacle_order");
        assertThat(session.path("judge_id").isNull()).isTrue();
        assertThat(session.path("started_at").asText()).isEqualTo("2026-10-08T08:00:00Z");
        assertThat(session.path("completed_at").isNull()).isTrue();
        assertThat(session.at("/obstacle_statuses/0/status").asText()).isEqualTo("placed");
        assertThat(session.at("/obstacle_statuses/0/updated_at").asText()).isEqualTo("2026-10-08T08:05:00Z");
        assertThat(session.at("/obstacle_statuses/1/status").asText()).isEqualTo("needs_check");
        assertThat(session.at("/obstacle_statuses/1/updated_at").isNull()).isTrue();
        var placement = export.path("placement");
        assertThat(placement.path("placement_mode").asText()).isEqualTo("fitToRing");
        assertThat(placement.path("status").asText()).isEqualTo("ready_to_build");
        assertThat(placement.path("venue_id").asText()).isEqualTo("venue-a");
        assertThat(placement.path("is_training").asBoolean()).isTrue();
        assertThat(placement.path("course_type").asText()).isEqualTo("Jumping");
        assertThat(placement.path("event_id").isNull()).isTrue();
        assertThat(placement.path("flip_x_bool").asBoolean()).isTrue();
        assertThat(placement.path("rotation_deg").asDouble()).isEqualTo(90);
        assertThat(placement.path("sizes")).extracting(node -> node.asText()).containsExactly("S", "L");
        assertThat(placement.at("/warnings_json/0/ruleId").asText()).isEqualTo("border-clearance");
        assertThat(placement.at("/warnings_json/0/severity").asText()).isEqualTo("warning");
        var course = export.path("course");
        assertThat(course.path("title").asText()).isEqualTo("Recorregut del club");
        assertThat(course.path("units").asText()).isEqualTo("m");
        assertThat(course.path("source").asText()).isEqualTo("smarter-agility");
        assertThat(course.path("normalized_json")).isEqualTo(courseData());
        assertThat(course.at("/theme/palette_key").asText()).isEqualTo("standard");
        assertThat(export.at("/venue/indoor_outdoor").asText()).isEqualTo("mixed");
        assertThat(export.at("/venue/surface").asText()).isEqualTo("sand");
        var ring = export.path("ring");
        assertThat(ring.path("venue_id").asText()).isEqualTo("venue-a");
        assertThat(ring.path("surface").asText()).isEqualTo("grass");
        assertThat(ring.at("/doors/0/side").asText()).isEqualTo("north");
        assertThat(ring.at("/doors/0/flow").asText()).isEqualTo("in");
        assertThat(ring.at("/markers")).hasSize(6);
        assertThat(ring.at("/markers/5/label").asText()).isEqualTo("F");
        assertThat(export.at("/assetProfile/profileId").asText()).isEqualTo("generic-fci");
    }

    @Test void T_16_03_otherDisciplinesAndModesDeriveTheirPlannerColumns() throws Exception {
        var event = export("EVENT", "AGILITY", 6);
        schemas.validateExport(event);
        assertThat(event.at("/placement/is_training").asBoolean()).isFalse();
        assertThat(event.at("/placement/course_type").asText()).isEqualTo("Agility");
        var other = export("FREE_FLOATING", "OTHER", 6);
        schemas.validateExport(other);
        assertThat(other.at("/placement/course_type").isNull()).isTrue();
        assertThat(BuildSessionExport.camel(com.agilityhub.core.courses.domain.CourseTypes.PlacementMode.PRESERVE_METERS)).isEqualTo("preserveMeters");
        assertThat(BuildSessionExport.camel(com.agilityhub.core.courses.domain.CourseTypes.PlacementMode.CENTERED)).isEqualTo("centered");
        assertThat(BuildSessionExport.planner(null)).isNull();
    }

    /** R-16-04 accepts three active markers, the Unity export exactly six: a four-marker ring cannot pass, so the route refuses it first. */
    @Test void T_16_03_aRingWithoutSixMarkersCannotPassTheSchema() throws Exception {
        assertThatThrownBy(() -> schemas.validateExport(export("TRAINING", "AGILITY", 4))).isInstanceOfSatisfying(ApiException.class,
                failed -> assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }
}
