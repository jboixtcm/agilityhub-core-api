package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseContractGuards;
import com.agilityhub.core.courses.application.CourseSchemas;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.courses.api.CourseContracts.*;
import static com.agilityhub.core.courses.api.CourseRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/** S16 contract. All checks precede the no-write NOT_IMPLEMENTED boundary. */
@RestController
@RequiresModule(Module.COURSES)
public class CoursesController {
    private final CourseContractGuards guards;
    private final CourseSchemas schemas;
    public CoursesController(CourseContractGuards guards, CourseSchemas schemas) { this.guards = guards; this.schemas = schemas; }
    private void validateModel(Integer version, com.fasterxml.jackson.databind.JsonNode model) {
        if (version != null || model != null && !model.isNull()) { schemas.validateCourseData(version, model); }
    }

    @GetMapping(value = "/api/v1/courses")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @ListContract(paged = true, filterable = {"owner", "discipline", "agilityhubLevel", "levelId", "source", "tag", "designerName", "obstacleCount", "updatedAt"},
            sortable = {"name", "updatedAt"}, fields = {"id", "name", "discipline", "level", "designerName", "source", "thumbnailUrl", "stats", "ownerType", "activeOnRings"},
            columns = {"name*", "discipline*", "level*", "designerName*", "source*", "thumbnailUrl*", "stats*", "ownerType*", "activeOnRings*"})
    @Operation(summary = "listCourses", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED. Default sort: updatedAt,desc. owner: CLUB, AGILITYHUB or MINE.",
            responses = @ApiResponse(responseCode = "200", description = "S16 CoursePage", useReturnTypeSchema = true))
    public CoursePage listCourses(@Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String,String> query) {
        com.agilityhub.core.courses.application.CourseListContract.validate(query);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/courses")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED})
    @Operation(summary = "createCourse", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Course", useReturnTypeSchema = true))
    public Course createCourse(@Valid @RequestBody CourseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.levels(request.levelIds()); validateModel(request.schemaVersion(), request.normalizedJson());
        throw new UnsupportedOperationException("S16 contract");
    }

    @GetMapping(value = "/api/v1/courses/{id}")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR', 'ADMIN')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE})
    @Operation(summary = "getCourse", description = "Roles: MEMBER, INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Course", useReturnTypeSchema = true))
    public Course getCourse(@PathVariable String id) {
        guards.course(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PatchMapping(value = "/api/v1/courses/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, STALE_VERSION, COURSE_NOT_VISIBLE, COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED})
    @Operation(summary = "patchCourse", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Course", useReturnTypeSchema = true))
    public Course patchCourse(@PathVariable String id,
            @Valid @RequestBody CoursePatchRequest request) {
        guards.course(id, true); guards.levels(request.levelIds()); validateModel(request.schemaVersion(), request.normalizedJson());
        throw new UnsupportedOperationException("S16 contract");
    }

    @DeleteMapping(value = "/api/v1/courses/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_IN_USE, STALE_VERSION, COURSE_NOT_VISIBLE})
    @Operation(summary = "deleteCourse", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "204", description = "S16 void", content = @Content))
    public void deleteCourse(@PathVariable String id,
            @RequestParam @jakarta.validation.constraints.PositiveOrZero long version) {
        guards.course(id, true);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/courses/{id}/duplicate")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE})
    @Operation(summary = "duplicateCourse", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Course", useReturnTypeSchema = true))
    public Course duplicateCourse(@PathVariable String id,
            @Valid @RequestBody CourseCopyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.course(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/courses/{id}/copy-to-club")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE})
    @Operation(summary = "copyCourseToClub", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Course", useReturnTypeSchema = true))
    public Course copyCourseToClub(@PathVariable String id,
            @Valid @RequestBody CourseCopyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        guards.course(id, false);
        throw new UnsupportedOperationException("S16 contract");
    }

    @PostMapping(value = "/api/v1/courses/upload-urls")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, FILE_TYPE_NOT_ALLOWED, FILE_TOO_LARGE})
    @Operation(summary = "courseUploadUrl", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. E9 contract; guards then NOT_IMPLEMENTED.",
            responses = @ApiResponse(responseCode = "201", description = "S16 UploadUrl", useReturnTypeSchema = true))
    public UploadUrl courseUploadUrl(@Valid @RequestBody UploadUrlRequest request) {
        throw new UnsupportedOperationException("S16 contract");
    }
}
