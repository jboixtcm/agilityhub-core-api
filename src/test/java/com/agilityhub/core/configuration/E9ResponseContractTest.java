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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @ParameterizedTest
    @ValueSource(strings = {"RingSetup", "MemberRingSetup"})
    void T_16_09_setupFixturesMatchExactlyOnePublishedResponseBranch(String name) throws Exception {
        assertSetupResponse(mapper.readTree(Path.of("docs/openapi/openapi.json").toFile()), name);
    }

    static void assertSetupResponse(JsonNode api, String name) throws Exception {
        var response = api.at("/paths/~1api~1v1~1ring-setups~1{id}/get/responses/200/content/application~1json/schema");
        assertThat(response.isObject()).as("Actual GET /ring-setups/{id} response schema").isTrue();
        var fixture = E9Fixtures.response(name);
        var validator = responseValidator(api, response);
        assertThat(validator.validate(fixture)).as(name + " validates against the endpoint oneOf").isEmpty();
        String expectedView = name.equals("RingSetup") ? "STAFF" : "MEMBER";
        assertThat(fixture.path("view").asText()).isEqualTo(expectedView);
        assertThat(response.at("/discriminator/propertyName").asText()).isEqualTo("view");
        assertThat(response.path("discriminator").path("mapping").path(expectedView).asText())
                .isEqualTo("#/components/schemas/" + name);
        assertThat(response.path("oneOf")).hasSize(2);
        for (var branch : response.path("oneOf")) {
            String reference = branch.path("$ref").asText();
            boolean expected = reference.equals("#/components/schemas/" + name);
            assertThat(responseValidator(api, branch).validate(fixture).isEmpty()).as(reference).isEqualTo(expected);
        }
        var shape = api.at("/components/schemas").path(name);
        assertThat(strings(shape.path("required"))).contains("view");
        assertThat(shape.at("/properties/view/const").asText()).isEqualTo(expectedView);
        var missing = fixture.deepCopy(); missing.remove("view");
        assertThat(validator.validate(missing)).as("view is required").isNotEmpty();
        assertThat(validator.validate(fixture.deepCopy().put("view", "UNKNOWN"))).isNotEmpty();
        assertThat(validator.validate(fixture.deepCopy().putNull("view"))).isNotEmpty();
    }

    private static com.networknt.schema.JsonSchema responseValidator(JsonNode api, JsonNode response) {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) response.deepCopy();
        root.set("components", api.path("components"));
        return com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(root);
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
    @Test void E9_T01_coreEnumsAndParameterDefaultsAreTheSpecLists() {
        Map<Class<? extends Enum<?>>,String> enums=new LinkedHashMap<>();
        enums.put(OwnerType.class,"CLUB,AGILITYHUB,ACCOUNT");enums.put(Visibility.class,"PRIVATE,CLUB,PUBLIC");enums.put(Discipline.class,"AGILITY,JUMPING,OTHER");
        enums.put(CourseSource.class,"SMARTER,EDITOR,IMAGE,AGILITYHUB_COPY");enums.put(AgilityHubLevel.class,"EASY,MEDIUM,HARD");enums.put(SizeCategory.class,"GRAND,GARDEN");
        enums.put(PlacementMode.class,"PRESERVE_METERS,CENTERED,FIT_TO_RING");enums.put(PlacementStatus.class,"DRAFT,READY_TO_BUILD,ARCHIVED");enums.put(PlacementUse.class,"EVENT,TRAINING,FREE_FLOATING");
        enums.put(DogSize.class,"XS,S,M,I,L,ALL");enums.put(SetupKind.class,"AGILITY,JUMPING,FUN,OBSTACLE_DRILL,EMPTY");enums.put(SetupStatus.class,"ACTIVE,EXPIRED,DISMANTLED");
        enums.put(DismantledReason.class,"REPLACED,MANUAL,RING_INACTIVE"); // INC-62 (E11-T02)
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
    @Test void E9_T01_errorStatusesFollowTheCatalogIncludingFinished422() {
        Map<Integer,String> codes=Map.of(400,"COURSE_MODEL_INVALID,SCHEMA_VERSION_UNSUPPORTED,FILE_TYPE_NOT_ALLOWED,FILE_TOO_LARGE,VALIDATION_ERROR",
                403,"FORBIDDEN,IMPERSONATION_DENIED",404,"COURSE_NOT_VISIBLE,NOT_FOUND,MODULE_DISABLED",409,"STALE_VERSION,INVALID_STATE,COURSE_IN_USE,RING_GEOMETRY_IN_USE",
                422,"GEOMETRY_INVALID,PLACEMENT_BLOCKED,RING_WITHOUT_GEOMETRY,PLACEMENT_RING_MISMATCH,SETUP_SOURCE_REQUIRED,INVENTORY_INVALID,BUILD_SESSION_FINISHED",501,"NOT_IMPLEMENTED");
        codes.forEach((status,names)->{for(String name:names.split(",")) assertThat(ErrorCode.valueOf(name).httpStatus()).as(name).isEqualTo(status);});
    }
    /** E9-T01's 42 operations, less `GET /rings/{id}/marker-sheet` (E115: the client makes the QR marker sheet, A8b). */
    @Test void T_16_03_T_16_21_allFortyOneOperationsPublishTypedFormsHeadersAndCanonicalErrors() throws Exception {
        var api=mapper.readTree(Path.of("docs/openapi/openapi.json").toFile());var routes=E9Fixtures.read("contracts/e9-routes");assertThat(routes).hasSize(41);
        assertThat(api.path("paths").has("/api/v1/rings/{id}/marker-sheet")).isFalse();
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
            // INC-62 (E11-T02): `updatedAt`, D18's date column, is a field of the list too.
            assertThat(strings(get.path("x-fields"))).containsExactlyInAnyOrder("id","name","discipline","level","designerName","source","thumbnailUrl","stats","ownerType","activeOnRings","updatedAt");
            assertThat(strings(get.path("x-sortable"))).containsExactly("name","updatedAt");
            // E11-T17 (E154): D18 shows `updatedAt` by default, so the column catalogue publishes it, visible by default.
            assertThat(get.path("x-columns").findValuesAsText("key")).containsExactly("name","discipline","level","designerName","source","thumbnailUrl","stats","ownerType","activeOnRings","updatedAt");
            assertThat(get.at("/x-columns/9/defaultVisible").asBoolean()).isTrue();
            assertThat(get.path("description").asText()).contains("updatedAt,desc");
        }
        assertThat(strings(api.at("/components/schemas/CourseListItem/required"))).containsExactly("id");
        assertThat(api.at("/components/schemas/CourseDataV1")).isEqualTo(schema("course-data"));
        assertThat(api.at("/components/schemas/BuildSessionExportV1")).isEqualTo(schema("build-session-export"));
        assertThat(api.at("/components/schemas/PlacementWarning"))
                .isEqualTo(schema("build-session-export").at("/properties/placement/properties/warnings_json/items"));
        assertThat(api.at("/paths/~1api~1v1~1build-sessions~1{id}~1events/get/parameters").findValuesAsText("name")).doesNotContain("access_token");
    }

    /**
     * T-16-10 «token per query només a `/events`» (E9-T04): the stream publishes two alternatives, the bearer header or the
     * `access_token` query scheme, and no other operation names that scheme.
     */
    @Test void T_16_10_onlyTheEventStreamPublishesTheQueryTokenAlternative() throws Exception {
        var api=mapper.readTree(Path.of("docs/openapi/openapi.json").toFile());
        var scheme=api.at("/components/securitySchemes/accessTokenQuery");
        assertThat(scheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asText()).isEqualTo("query");
        assertThat(scheme.path("name").asText()).isEqualTo("access_token");
        assertThat(api.at("/paths/~1api~1v1~1build-sessions~1{id}~1events/get/security").toString())
                .isEqualTo("[{\"bearer\":[]},{\"accessTokenQuery\":[]}]");
        var users=new java.util.ArrayList<String>();
        for(var path:api.path("paths").properties()) for(var operation:path.getValue().properties()) {
            if(operation.getValue().path("security").toString().contains("accessTokenQuery")) users.add(operation.getKey()+" "+path.getKey());
        }
        assertThat(users).containsExactly("get /api/v1/build-sessions/{id}/events");
    }

    @Test void T_16_05_courseFixturesUseVisibilityAllowedForTheirOwner() throws Exception {
        var club=E9Fixtures.document(com.agilityhub.core.courses.persistence.Course.class,"course-a","club-a");
        var library=E9Fixtures.document(com.agilityhub.core.courses.persistence.Course.class,"course-public",null);
        assertThat(club.ownerType()).isEqualTo(OwnerType.CLUB);
        assertThat(club.visibility()).isEqualTo(Visibility.CLUB);
        assertThat(library.ownerType()).isEqualTo(OwnerType.AGILITYHUB);
        assertThat(library.visibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test void T_19_01_attemptCreationPublishes201WhileR1RemainsAStub() throws Exception {
        var api=mapper.readTree(Path.of("docs/openapi/openapi.json").toFile());
        var responses=api.at("/paths/~1api~1v1~1challenges~1{id}~1attempts/post/responses");
        assertThat(responses.has("201")).isTrue();
        assertThat(responses.has("200")).isFalse();
        assertThat(responses.at("/201/content/application~1json/schema/$ref").asText()).endsWith("/ChallengeAttempt");
        assertThat(responses.at("/501/description").asText()).isEqualTo("NOT_IMPLEMENTED");
    }

    @Test void E9_T01_anyJsonFieldsPublishLanguageNeutralSchemasWithoutNarrowingValues() throws Exception {
        var api=mapper.readTree(Path.of("docs/openapi/openapi.json").toFile());
        var schemas=api.at("/components/schemas");
        assertThat(schemas.has("JsonNode")).isFalse();
        assertThat(api.findValuesAsText("$ref")).doesNotContain("#/components/schemas/JsonNode");
        for(var entry:Map.of("CalibrationLog","rawMetadata","CalibrationRequest","rawMetadata",
                "BuildSession","progressJson","Challenge","rules").entrySet()) {
            var field=schemas.path(entry.getKey()).path("properties").path(entry.getValue());
            assertThat(field.path("$ref").asText()).isEqualTo("#/components/schemas/AnyJson");
        }
        assertThat(schemas.path("AnyJson").isObject()).isTrue();
        assertThat(schemas.path("AnyJson")).isEmpty(); // {} accepts objects, arrays, scalars and null.
        assertThat(strings(schemas.at("/CalibrationRequest/required"))).contains("rawMetadata");
        assertThat(strings(schemas.at("/BuildSession/required"))).doesNotContain("progressJson");
    }

    /**
     * E9-T07 steps 1 and 2 (E9-W04 questions 1 and 2; S16 §2, R-16-16): the staff's setup names who built it (`builtByName`); the
     * member's never does, and carries, for a setup with a placement, where it stands on the ring (`placement`: the transform, the
     * resolved obstacles and the ring's size), so the members' viewer draws its course in the ring's frame.
     */
    @Test void T_16_09_T_16_16_theStaffSetupNamesItsBuilderAndTheMemberSetupCarriesItsPlacement() throws Exception {
        assertThat(components(CourseContracts.RingSetup.class)).contains("builtByAccountId", "builtByName");
        assertThat(components(CourseContracts.MemberRingSetup.class)).contains("courseId", "placement").doesNotContain("builtByName");
        var schemas=mapper.readTree(Path.of("docs/openapi/openapi.json").toFile()).at("/components/schemas");
        assertThat(strings(schemas.at("/RingSetup/properties/builtByName/type"))).containsExactly("string", "null");
        assertThat(strings(schemas.at("/RingSetup/required"))).doesNotContain("builtByName");
        assertThat(schemas.at("/MemberRingSetup/properties").has("builtByName")).isFalse();
        assertThat(schemas.at("/MemberRingSetup/properties/placement/anyOf/0/$ref").asText()).isEqualTo("#/components/schemas/MemberSetupPlacement");
        assertThat(schemas.at("/MemberSetupPlacement/properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("transform", "resolvedObstacles", "ringLengthM", "ringWidthM");
        assertThat(strings(schemas.at("/MemberSetupPlacement/required"))).containsExactlyInAnyOrder("transform", "resolvedObstacles");
    }
    /**
     * E9-T07 step 4 (E109; E9-W04 question 4 and review #9): S16 follows the contract. §2's build session lists the obstacle statuses
     * the api serves, and §6 and T-16-10 answer a closed session with `422 BUILD_SESSION_FINISHED`, the catalog's status.
     */
    @Test void T_16_10_theSpecNamesTheContractsObstacleStatusesAndFinishedError() throws Exception {
        var spec=Files.readString(Path.of("docs/specs/S16-recorreguts-rings-i-muntatge.md"));
        assertThat(ErrorCode.BUILD_SESSION_FINISHED.httpStatus()).isEqualTo(422);
        assertThat(spec).doesNotContain("409 BUILD_SESSION_FINISHED").doesNotContain("PENDING · PLACED · VERIFIED").doesNotContain("pendent · col·locat · verificat");
        var lines=spec.lines().toList();
        String api=lines.stream().filter(l->l.contains("POST `/build-sessions/{id}/finish`")).findFirst().orElseThrow();
        String test=lines.stream().filter(l->l.startsWith("- T-16-10 ")).findFirst().orElseThrow();
        String screen=lines.stream().filter(l->l.startsWith("| **Sessió de muntatge**")).findFirst().orElseThrow();
        assertThat(api).contains("422 BUILD_SESSION_FINISHED");
        assertThat(test).contains("422 BUILD_SESSION_FINISHED");
        for(var status:ObstacleStatus.values()) { assertThat(screen).contains("`"+status.name()+"`"); }
    }
    static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
    }
    @Test void T_16_06_T_16_09_geometryAndHiddenSetupSemanticsStayInThePublishedContract() throws Exception {
        var api=mapper.readTree(Path.of("docs/openapi/openapi.json").toFile());
        for(String field:List.of("lengthM","widthM")) {
            var shape=api.at("/components/schemas/RingGeometryRequest/properties").path(field);
            assertThat(shape.path("minimum").asDouble()).isEqualTo(10);
            assertThat(shape.path("maximum").asDouble()).isEqualTo(60);
        }
        assertThat(api.at("/paths/~1api~1v1~1rings~1{id}~1geometry/put/responses/422/description").asText()).contains("GEOMETRY_INVALID");
        assertThat(api.at("/paths/~1api~1v1~1me~1ring-setups/get/description").asText())
                .contains("courses.showSetupToMembers", "setup = null", "MEMBER", "INSTRUCTOR");
    }
}
