package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseLibrary;
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

/** S16 R-16-14 (E9-T02): the AgilityHub library, global, for AGILITYHUB_ADMIN only. */
@RestController
public class PlatformCoursesController {
    private final CourseLibrary library;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public PlatformCoursesController(CourseLibrary library, com.fasterxml.jackson.databind.ObjectMapper mapper) { this.library = library; this.mapper = mapper; }

    @GetMapping(value = "/api/v1/platform/courses")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED})
    @ListContract(paged = true, filterable = {"owner", "discipline", "agilityhubLevel", "levelId", "source", "tag", "designerName", "obstacleCount", "updatedAt"},
            sortable = {"name", "updatedAt"}, fields = {"id", "name", "discipline", "level", "designerName", "source", "thumbnailUrl", "stats", "ownerType", "activeOnRings", "updatedAt"},
            columns = {"name*", "discipline*", "level*", "designerName*", "source*", "thumbnailUrl*", "stats*", "ownerType*", "activeOnRings*"})
    @Operation(summary = "listPlatformCourses", description = "Roles: AGILITYHUB_ADMIN. Global; no tenant or module. The AgilityHub library (ownerType AGILITYHUB) with the list contract of /courses. Default sort: updatedAt,desc. owner: CLUB, AGILITYHUB or MINE.",
            responses = @ApiResponse(responseCode = "200", description = "S16 CoursePage", useReturnTypeSchema = true))
    public org.springframework.http.ResponseEntity<CoursePage> listPlatformCourses(@Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String,String> query) {
        return CourseViews.page(mapper, library.listLibrary(query));
    }

    @PostMapping(value = "/api/v1/platform/courses")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED})
    @Operation(summary = "createPlatformCourse", description = "Roles: AGILITYHUB_ADMIN. Global; no tenant or module. R-16-14: agilityhubLevel required and visibility PUBLIC, otherwise VALIDATION_ERROR; ownerType AGILITYHUB, no club. levelIds and file keys name a club's data and are NOT_FOUND here. The model rules of POST /courses apply. Publishes CourseImported or CourseCreated with clubId null.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Course", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Course createPlatformCourse(@Valid @RequestBody CourseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.course(library.createLibrary(CourseViews.input(request)));
    }

    @PatchMapping(value = "/api/v1/platform/courses/{id}")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, STALE_VERSION, COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED})
    @Operation(summary = "patchPlatformCourse", description = "Roles: AGILITYHUB_ADMIN. Global; no tenant or module. R-16-14: an omitted agilityhubLevel keeps the stored one; a visibility other than PUBLIC is VALIDATION_ERROR. Only an AGILITYHUB course (else NOT_FOUND), at its stored version (STALE_VERSION). Publishes CourseUpdated.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Course", useReturnTypeSchema = true))
    public Course patchPlatformCourse(@PathVariable String id,
            @Valid @RequestBody CoursePatchRequest request) {
        return CourseViews.course(library.patchLibrary(id, CourseViews.input(request), request.version()));
    }
}
