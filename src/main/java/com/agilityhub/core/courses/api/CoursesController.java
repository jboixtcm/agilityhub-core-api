package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.CourseFiles;
import com.agilityhub.core.courses.application.CourseLibrary;
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

/** S16 course library (E9-T02): R-16-01…03, R-16-16, R-16-17. */
@RestController
@RequiresModule(Module.COURSES)
public class CoursesController {
    private final CourseLibrary library;
    private final CourseFiles files;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public CoursesController(CourseLibrary library, CourseFiles files, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.library = library; this.files = files; this.mapper = mapper;
    }

    @GetMapping(value = "/api/v1/courses")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED})
    @ListContract(paged = true, filterable = {"owner", "discipline", "agilityhubLevel", "levelId", "source", "tag", "designerName", "obstacleCount", "updatedAt"},
            sortable = {"name", "updatedAt"}, fields = {"id", "name", "discipline", "level", "designerName", "source", "thumbnailUrl", "stats", "ownerType", "activeOnRings", "updatedAt"},
            columns = {"name*", "discipline*", "level*", "designerName*", "source*", "thumbnailUrl*", "stats*", "ownerType*", "activeOnRings*", "updatedAt*"})
    @Operation(summary = "listCourses", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-03: the club's courses the caller may read (a PRIVATE one only for its author), the AgilityHub library (PUBLIC) and the caller's own account courses; never another club's. Default sort: updatedAt,desc; name sorts by the name in the request's language. owner: CLUB, AGILITYHUB or MINE (the caller's account courses). level: the club levels' codes joined by an en dash, else the agilityhubLevel. activeOnRings: the rings whose active setup shows the course.",
            responses = @ApiResponse(responseCode = "200", description = "S16 CoursePage", useReturnTypeSchema = true))
    public org.springframework.http.ResponseEntity<CoursePage> listCourses(@Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String,String> query) {
        return CourseViews.page(mapper, library.list(query));
    }

    @PostMapping(value = "/api/v1/courses")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED, LEVELS_DISABLED})
    @Operation(summary = "createCourse", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. Creates a CLUB course. S16 §3: club-owned courses cannot use PUBLIC visibility (VALIDATION_ERROR). R-16-01: normalizedJson (required unless source = IMAGE, else COURSE_MODEL_INVALID) is validated against course-data.v1 with schemaVersion 1; the server reads designLengthM, designWidthM, canvas, origin, courseGrade, courseType and stats from it. source = IMAGE requires imageFileKey; AGILITYHUB_COPY only comes from copy-to-club (VALIDATION_ERROR). File keys must be this club's uploads of the matching purpose (sourceFileKey SMARTER_SOURCE, imageFileKey COURSE_IMAGE, thumbnailFileKey THUMBNAIL), else NOT_FOUND. name: enabled club locales (LOCALE_NOT_ENABLED). levelIds: none while levels.enabled = false (LEVELS_DISABLED), else this club's levels (NOT_FOUND). Publishes CourseImported (SMARTER) or CourseCreated.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Course", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Course createCourse(@Valid @RequestBody CourseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.course(library.create(CourseViews.input(request)));
    }

    @GetMapping(value = "/api/v1/courses/{id}")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR', 'ADMIN')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE})
    @Operation(summary = "getCourse", description = "Roles: MEMBER, INSTRUCTOR, ADMIN. Module: COURSES. MEMBER reads (including impersonation) require courses.showSetupToMembers; otherwise NOT_FOUND. R-16-03: another club's or a deleted course is NOT_FOUND. INSTRUCTOR and ADMIN read their club's CLUB courses (a PRIVATE one only its author), PUBLIC courses and their own account courses. A MEMBER reads a PUBLIC course and, R-16-16, any course (a CLUB one included) that an ACTIVE setup of the club shows, directly (courseId) or through its placement; any other course is COURSE_NOT_VISIBLE. name is resolved in the request's language with the club's default as fallback (R-16-17); nameI18n is sent only to a caller who can edit the course.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Course", useReturnTypeSchema = true))
    public Course getCourse(@PathVariable String id) {
        return CourseViews.course(library.get(id));
    }

    @PatchMapping(value = "/api/v1/courses/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, STALE_VERSION, COURSE_NOT_VISIBLE, COURSE_MODEL_INVALID, SCHEMA_VERSION_UNSUPPORTED, LEVELS_DISABLED})
    @Operation(summary = "patchCourse", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. S16 §3: PUBLIC visibility is allowed for an ACCOUNT author, never a CLUB owner (VALIDATION_ERROR). A null or absent field keeps its value; the AgilityHub library is never editable from a club (FORBIDDEN). Edits are in place: a new model keeps the previous one in history (the last 10). version must be the stored one (STALE_VERSION). Publishes CourseUpdated{courseId, diff} when something changes.",
            responses = @ApiResponse(responseCode = "200", description = "S16 Course", useReturnTypeSchema = true))
    public Course patchCourse(@PathVariable String id,
            @Valid @RequestBody CoursePatchRequest request) {
        return CourseViews.course(library.patch(id, CourseViews.input(request), request.version()));
    }

    @DeleteMapping(value = "/api/v1/courses/{id}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_IN_USE, STALE_VERSION, COURSE_NOT_VISIBLE})
    @Operation(summary = "deleteCourse", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. Soft delete (deletedAt) at the stored version (STALE_VERSION); COURSE_IN_USE while an active setup shows the course, directly or through a placement. Publishes CourseDeleted.",
            responses = @ApiResponse(responseCode = "204", description = "S16 void", content = @Content))
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteCourse(@PathVariable String id,
            @RequestParam @jakarta.validation.constraints.PositiveOrZero long version) {
        library.delete(id, version);
    }

    @PostMapping(value = "/api/v1/courses/{id}/duplicate")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE})
    @Operation(summary = "duplicateCourse", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. A copy with the same owner, visibility and model, version 0; the caller must be able to edit the original (the AgilityHub library is copied with copy-to-club: FORBIDDEN). name defaults to the original's. Publishes CourseCopied.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Course", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Course duplicateCourse(@PathVariable String id,
            @Valid @RequestBody CourseCopyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.course(library.duplicate(id, request.name()));
    }

    @PostMapping(value = "/api/v1/courses/{id}/copy-to-club")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, COURSE_NOT_VISIBLE})
    @Operation(summary = "copyCourseToClub", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-03: a PUBLIC course (else VALIDATION_ERROR) becomes this club's editable CLUB course with source AGILITYHUB_COPY and sourceCourseId; later changes of the original never reach the copy. Club levels are not copied. Publishes CourseCopied.",
            responses = @ApiResponse(responseCode = "201", description = "S16 Course", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Course copyCourseToClub(@PathVariable String id,
            @Valid @RequestBody CourseCopyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return CourseViews.course(library.copyToClub(id, request.name()));
    }

    @PostMapping(value = "/api/v1/courses/upload-urls")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    @ContractErrors({VALIDATION_ERROR, FORBIDDEN, IMPERSONATION_DENIED, NOT_FOUND, NOT_IMPLEMENTED, MODULE_DISABLED, FILE_TYPE_NOT_ALLOWED, FILE_TOO_LARGE})
    @Operation(summary = "courseUploadUrl", description = "Roles: INSTRUCTOR, ADMIN. Module: COURSES. R-16-16: contentType ∈ files.allowedTypes and fit for the purpose (SMARTER_SOURCE text/plain only; COURSE_IMAGE and THUMBNAIL image/*; BUILD_SHEET application/pdf) → else FILE_TYPE_NOT_ALLOWED; 0 < size ≤ files.maxSizeMb, and ≤ 512 KB for SMARTER_SOURCE (R-16-02) → else FILE_TOO_LARGE (CourseFileTooLargeDetails {maxSizeMb}: 0.5 for SMARTER_SOURCE). fileKey = {clubId}/courses/{uuid}; the uploadUrl is signed and expires after 5 minutes (PUT with the same Content-Type).",
            responses = @ApiResponse(responseCode = "201", description = "S16 UploadUrl", useReturnTypeSchema = true))
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public UploadUrl courseUploadUrl(@Valid @RequestBody UploadUrlRequest request) {
        var upload = files.upload(request.purpose(), request.contentType(), request.size());
        return new UploadUrl(upload.uploadUrl(), upload.fileKey());
    }
}
