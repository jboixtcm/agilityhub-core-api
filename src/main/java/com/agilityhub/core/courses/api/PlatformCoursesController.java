package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseContractGuards;
import com.agilityhub.core.courses.application.CourseSchemas;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.shared.application.contract.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S16 contract. All checks precede the no-write NOT_IMPLEMENTED boundary. */
@RestController
public class PlatformCoursesController {
    private final CourseContractGuards guards;
    private final CourseSchemas schemas;
    public PlatformCoursesController(CourseContractGuards guards, CourseSchemas schemas) { this.guards = guards; this.schemas = schemas; }
    private void validateModel(Integer version, com.fasterxml.jackson.databind.JsonNode model) {
        if (version != null || model != null && !model.isNull()) { schemas.validateCourseData(version, model); }
    }

    @GetMapping(value = "/api/v1/platform/courses")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED})
    @ListContract(paged = true, filterable = {"owner", "discipline", "agilityhubLevel", "levelId", "source", "tag", "designerName", "obstacleCount", "updatedAt"},
            sortable = {"name", "updatedAt"}, fields = {"id", "name", "discipline", "level", "designerName", "source", "thumbnailUrl", "stats", "ownerType", "activeOnRings"},
            columns = {"name*", "discipline*", "level*", "designerName*", "source*", "thumbnailUrl*", "stats*", "ownerType*", "activeOnRings*"})
    @Operation(summary = "listPlatformCourses", description = "Roles: AGILITYHUB_ADMIN. Global; no tenant or module. E9 contract; guards then NOT_IMPLEMENTED. Default sort: updatedAt,desc. owner: CLUB, AGILITYHUB or MINE.",
            responses = @ApiResponse(responseCode = "200", description = "S16 CoursePage", useReturnTypeSchema = true))
    public CoursePage listPlatformCourses(@Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String,String> query) {
        com.agilityhub.core.courses.application.CourseListContract.validate(query);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/platform/courses")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED})
    @Operation(summary = "createPlatformCourse", description = "Roles: AGILITYHUB_ADMIN. Global; no tenant or module. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Course", useReturnTypeSchema = true))
    public Course createPlatformCourse(@Valid @RequestBody CourseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.platformLevels(request.levelIds()); if (request.agilityhubLevel() == null) { throw new com.agilityhub.core.shared.domain.ApiException(VALIDATION_ERROR); } validateModel(request.schemaVersion(), request.normalizedJson());
        throw new UnsupportedOperationException("S16 contract");
    }

    @PatchMapping(value = "/api/v1/platform/courses/{id}")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, STALE_VERSION, COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED})
    @Operation(summary = "patchPlatformCourse", description = "Roles: AGILITYHUB_ADMIN. Global; no tenant or module. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Course", useReturnTypeSchema = true))
    public Course patchPlatformCourse(@PathVariable String id,
            @Valid @RequestBody CoursePatchRequest request) {
        guards.platformCourse(id); guards.platformLevels(request.levelIds()); if (request.agilityhubLevel() == null) { throw new com.agilityhub.core.shared.domain.ApiException(VALIDATION_ERROR); } validateModel(request.schemaVersion(), request.normalizedJson());
        throw new UnsupportedOperationException("S16 contract");
    }
}
