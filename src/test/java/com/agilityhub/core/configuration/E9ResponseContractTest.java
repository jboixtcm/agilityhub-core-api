package com.agilityhub.core.configuration;

import com.agilityhub.core.courses.api.*;
import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.*;

class E9ResponseContractTest {
    final ObjectMapper mapper=E9Fixtures.MAPPER;
    static List<String> strings(JsonNode node) { var r=new ArrayList<String>();node.forEach(n->r.add(n.asText()));return r; }
    JsonNode schema(String name) throws Exception {
        var schema=mapper.readTree(Path.of("src/main/resources/schemas/"+name+".v1.schema.json").toFile());
        canonicalRequired(schema);return schema;
    }
    /** The shared snapshot canonicalizer sorts required sets; it leaves every validation constraint unchanged. */
    void canonicalRequired(JsonNode node) {
        if(node.isObject()&&node.has("required")) {
            var names=strings(node.path("required"));Collections.sort(names);
            ((com.fasterxml.jackson.databind.node.ObjectNode)node).set("required",mapper.valueToTree(names));
        }
        node.forEach(this::canonicalRequired);
    }
    @Test void T_16_09_formsRoundTripEveryFieldAndNullAndMemberProjectionHasNoStaffFields() throws Exception {
        int forms=0;
        var published=mapper.readTree(Path.of("docs/openapi/openapi.json").toFile()).at("/components/schemas");
        for(String group:List.of("course","ring","build")) {
            var fields=E9Fixtures.read("contracts/e9-"+group+"-responses").fields();
            while(fields.hasNext()) {
                var entry=fields.next();Class<?> type;
                try { type=Class.forName(CourseContracts.class.getName()+"$"+entry.getKey()); }
                catch(ClassNotFoundException nested) { type=Class.forName(CourseParts.class.getName()+"$"+entry.getKey()); }
                String schemaName=entry.getKey().equals("UploadUrl")?"CourseUploadUrl":entry.getKey();
                assertThat(published.path(schemaName).path("properties").fieldNames()).as(schemaName).toIterable()
                        .containsExactlyInAnyOrderElementsOf(mapper.convertValue(entry.getValue(),Map.class).keySet());
                JSONAssert.assertEquals(entry.getKey(),entry.getValue().toString(),mapper.writeValueAsString(mapper.treeToValue(entry.getValue(),type)),true);forms++;
            }
        }
        for(Class<?> request:CourseRequests.class.getDeclaredClasses()) {
            String name=request.getSimpleName().equals("UploadUrlRequest")?"CourseUploadUrlRequest":request.getSimpleName();
            assertThat(published.path(name).path("properties").fieldNames()).as(name).toIterable()
                    .containsExactlyInAnyOrder(Arrays.stream(request.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new));
        }
        assertThat(forms).isEqualTo(CourseContracts.class.getDeclaredClasses().length + CourseParts.class.getDeclaredClasses().length);
        assertThat(Arrays.stream(CourseContracts.MemberRingSetup.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("builtByAccountId","dismantledByAccountId","notes");
        var empty=new CourseContracts.MeRingSetup("ring-a",null);
        assertThat(mapper.valueToTree(empty).path("setup").isNull()).isTrue();
    }
    @Test void T_16_03_warningKeysAndPlannerEnumsComeFromTheCommittedSchemas() throws Exception {
        var warning=schema("build-session-export").at("/properties/placement/properties/warnings_json/items");
        assertThat(Arrays.stream(CourseParts.PlacementWarning.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .containsExactlyInAnyOrderElementsOf(new ArrayList<>(mapper.convertValue(warning.path("properties"),Map.class).keySet()));
        assertThat(strings(mapper.valueToTree(WarningRule.values()))).containsExactlyElementsOf(strings(warning.at("/properties/ruleId/enum")));
        assertThat(strings(mapper.valueToTree(WarningSeverity.values()))).containsExactlyElementsOf(strings(warning.at("/properties/severity/enum")));
        assertThat(strings(mapper.valueToTree(ObstacleType.values()))).containsExactlyElementsOf(strings(schema("course-data").at("/properties/obstacles/items/properties/obstacleType/enum")));
    }
    @Test void T_16_16_coreEnumsAndParameterDefaultsAreTheSpecLists() {
        Map<Class<? extends Enum<?>>,String> enums=new LinkedHashMap<>();
        enums.put(OwnerType.class,"CLUB,AGILITYHUB,ACCOUNT");enums.put(Visibility.class,"PRIVATE,CLUB,PUBLIC");enums.put(Discipline.class,"AGILITY,JUMPING,OTHER");
        enums.put(CourseSource.class,"SMARTER,EDITOR,IMAGE,AGILITYHUB_COPY");enums.put(AgilityHubLevel.class,"EASY,MEDIUM,HARD");enums.put(SizeCategory.class,"GRAND,GARDEN");
        enums.put(PlacementMode.class,"PRESERVE_METERS,CENTERED,FIT_TO_RING");enums.put(PlacementStatus.class,"DRAFT,READY_TO_BUILD,ARCHIVED");enums.put(PlacementUse.class,"EVENT,TRAINING,FREE_FLOATING");
        enums.put(DogSize.class,"XS,S,M,I,L,ALL");enums.put(SetupKind.class,"AGILITY,JUMPING,FUN,OBSTACLE_DRILL,EMPTY");enums.put(SetupStatus.class,"ACTIVE,EXPIRED,DISMANTLED");
        enums.put(BuildStatus.class,"NOT_STARTED,IN_PROGRESS,COMPLETED,ABANDONED");enums.put(BuildStrategy.class,"EQUIPMENT_TYPE,CLOSER_OBSTACLE_ORDER,FREE_BUILD");
        enums.put(ObstacleStatus.class,"NOT_PLACED,CURRENT,PLACED,SKIPPED,NEEDS_CHECK");enums.put(JoinedVia.class,"QR,CODE");enums.put(InventoryScope.class,"CLUB,RING");
        enums.put(CalibrationOutcome.class,"GREEN,YELLOW,RED,ABORTED");enums.put(DoorSide.class,"NORTH,SOUTH,EAST,WEST");enums.put(DoorFlow.class,"IN,OUT,BOTH");
        enums.put(Surface.class,"GRASS,SAND,ARTIFICIAL,INDOOR,OTHER");enums.put(UploadPurpose.class,"SMARTER_SOURCE,COURSE_IMAGE,THUMBNAIL,BUILD_SHEET");
        enums.put(ChallengeStatus.class,"DRAFT,PUBLISHED,CLOSED");enums.put(AttemptStatus.class,"SUBMITTED,VALIDATED,REJECTED");
        enums.put(IndoorOutdoor.class,"INDOOR,OUTDOOR,MIXED");enums.put(VenueVisibility.class,"PRIVATE_LINK,MEMBERS_ONLY,PUBLIC_PREVIEW");enums.put(PartnerStatus.class,"INACTIVE,TRIAL,ACTIVE,EXPIRED");enums.put(PaperSize.class,"A4,LETTER");
        enums.forEach((type,values)->assertThat(Arrays.stream(type.getEnumConstants()).map(Enum::name)).containsExactly(values.split(",")));
        assertThat(enums).hasSize(CourseTypes.class.getDeclaredClasses().length+1);
        var catalog=new ParameterCatalog(mapper);
        Map<String,Object> expected=Map.of("setupAutoExpireDays",7,"defaultWarningThresholdM",1.5,"buildSessionMaxHours",12,"showSetupToMembers",true,"allowInstructorPublish",true);
        expected.forEach((key,value)->assertThat(catalog.defaultValue("courses."+key)).as(key).isEqualTo(value));
        assertThat(catalog.entries().keySet().stream().filter(k->k.startsWith("courses."))).hasSize(5);
        assertThat(catalog.get("courses.defaultWarningThresholdM").type()).isEqualTo("decimal");
        assertThat(catalog.defaultValue("messaging.notifyNewRingSetup")).isEqualTo(false);
        assertThat(((List<?>)catalog.defaultValue("files.allowedTypes")).contains("text/plain")).isTrue();
    }
    @Test void T_16_13_eventsAndN31MatchBothDirectionsOfTheirCatalogs() throws Exception {
        var events=E9Fixtures.read("contracts/e9-events");var catalog=Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"));
        assertThat(events.fieldNames()).toIterable().containsExactlyInAnyOrder(Arrays.stream(CourseEvent.Kind.values()).map(Enum::name).toArray(String[]::new));
        for(var kind:CourseEvent.Kind.values()) {
            var payload=events.path(kind.name());
            if(kind==CourseEvent.Kind.CourseImported) assertThat(payload.path("source").asText()).isEqualTo("SMARTER");
            var row=catalog.lines().filter(l->l.startsWith("| `"+kind.name()+"` |")).findFirst().orElseThrow();
            var fields=Arrays.stream(row.split("\\|",-1)[2].replaceAll("\\([^)]*\\)","").split(",")).map(String::strip).map(f->f.replaceAll("[?\\[\\]]","")).toList();
            assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(fields);
            @SuppressWarnings("unchecked") var map=(Map<String,Object>)mapper.convertValue(payload,Map.class);
            var event=new CourseEvent(kind,kind.name().startsWith("Course")?null:"club-a","aggregate-a",Instant.parse("2026-10-08T08:00:00Z"),map,"account-a",null,DomainEvent.Origin.BACKOFFICE);
            assertThat(event.type()).isEqualTo(kind.name());assertThat(event.payload()).isEqualTo(map);
            assertThat(event.aggregateType()).isEqualTo(kind.name().startsWith("Course")?"Course":kind.name().startsWith("Placement")?"Placement":kind.name().startsWith("Build")?"BuildSession":kind==CourseEvent.Kind.RingGeometryChanged?"Ring":kind==CourseEvent.Kind.RingSetupChanged?"RingSetup":"ObstacleInventory");
            assertThat(mapper.readValue(mapper.writeValueAsString(event),CourseEvent.class)).isEqualTo(event);
            assertThatThrownBy(()->event.payload().put("extra",true)).isInstanceOf(UnsupportedOperationException.class);
        }
        var fixture=E9Fixtures.read("contracts/e9-notifications").path("N-31");var notification=NotificationCatalog.byCode("N-31").orElseThrow();
        String row=Files.readString(Path.of("docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md")).lines().filter(l->l.startsWith("| N-31 |")).findFirst().orElseThrow();
        assertThat(notification.eventTypes()).contains(fixture.path("event").asText());assertThat(strings(fixture.path("variables"))).containsExactlyElementsOf(notification.variables());
        assertThat(notification.actions().values()).extracting(Enum::name).contains("OPEN_SETUP");
        for(String variable:strings(fixture.path("variables"))) assertThat(row).contains(variable);
        assertThat(row).contains("RingSetupChanged","OPEN_SETUP");
    }
    @Test void T_16_16_errorStatusesFollowTheCatalogIncludingFinished422() {
        Map<Integer,String> codes=Map.of(400,"COURSE_MODEL_INVALID,SCHEMA_VERSION_UNSUPPORTED,FILE_TYPE_NOT_ALLOWED,FILE_TOO_LARGE,VALIDATION_ERROR",
                403,"FORBIDDEN,IMPERSONATION_DENIED",404,"COURSE_NOT_VISIBLE,NOT_FOUND,MODULE_DISABLED",409,"STALE_VERSION,INVALID_STATE,COURSE_IN_USE,RING_GEOMETRY_IN_USE",
                422,"GEOMETRY_INVALID,PLACEMENT_BLOCKED,RING_WITHOUT_GEOMETRY,PLACEMENT_RING_MISMATCH,SETUP_SOURCE_REQUIRED,INVENTORY_INVALID,BUILD_SESSION_FINISHED",501,"NOT_IMPLEMENTED");
        codes.forEach((status,names)->{for(String name:names.split(",")) assertThat(ErrorCode.valueOf(name).httpStatus()).as(name).isEqualTo(status);});
    }
    @Test void T_16_03_T_16_21_allFortyTwoOperationsPublishTypedFormsHeadersAndCanonicalErrors() throws Exception {
        var api=mapper.readTree(Path.of("docs/openapi/openapi.json").toFile());var routes=E9Fixtures.read("contracts/e9-routes");assertThat(routes).hasSize(42);
        for(var route:routes) {
            var operation=api.path("paths").path(route.path("path").asText()).path(route.path("method").asText().toLowerCase());
            assertThat(operation.isMissingNode()).as(route.path("path").asText()).isFalse();
            assertThat(operation.path("responses").has(route.path("success").asText())).isTrue();
            assertThat(operation.path("parameters").findValuesAsText("name").contains("Idempotency-Key")).isEqualTo(route.path("idempotency").asBoolean());
            if(!route.path("body").isNull()) assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText()).startsWith("#/components/schemas/");
            assertThat(operation.at("/responses/501/description").asText()).isEqualTo("NOT_IMPLEMENTED");
            for(var role:route.path("roles")) assertThat(operation.path("description").asText()).contains(role.asText());
            if(!route.path("platform").asBoolean()) assertThat(operation.path("description").asText()).contains("COURSES");
            for(String status:List.of("400","403","404","409","422")) {
                String codes=operation.path("responses").path(status).path("description").asText();
                for(String code:codes.split(", ")) {
                    if(code.matches("[A-Z][A-Z_0-9]+")) assertThat(ErrorCode.valueOf(code).httpStatus()).as(route.path("path").asText()+" "+code).isEqualTo(Integer.parseInt(status));
                }
            }
        }
        for(String path:List.of("/api/v1/courses","/api/v1/platform/courses")) {
            var get=api.path("paths").path(path).path("get");
            assertThat(strings(get.path("x-fields"))).containsExactlyInAnyOrder("id","name","discipline","level","designerName","source","thumbnailUrl","stats","ownerType","activeOnRings");
            assertThat(strings(get.path("x-sortable"))).containsExactly("name","updatedAt");
            assertThat(get.path("description").asText()).contains("updatedAt,desc");
        }
        assertThat(strings(api.at("/components/schemas/CourseListItem/required"))).containsExactly("id");
        assertThat(api.at("/components/schemas/CourseDataV1")).isEqualTo(schema("course-data"));
        assertThat(api.at("/components/schemas/BuildSessionExportV1")).isEqualTo(schema("build-session-export"));
        assertThat(api.at("/components/schemas/PlacementWarning"))
                .isEqualTo(schema("build-session-export").at("/properties/placement/properties/warnings_json/items"));
        assertThat(api.at("/paths/~1api~1v1~1build-sessions~1{id}~1events/get/parameters").findValuesAsText("name")).doesNotContain("access_token");
    }
}
