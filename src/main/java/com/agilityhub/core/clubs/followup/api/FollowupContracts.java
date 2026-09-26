package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.domain.FollowupKind;
import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S10 §6 wire forms of screens 26, 13 (tasks), D14 and the observations of 22/D13, field by field from the examples.
 * Instants are UTC; nullable fields are optional and omitted when null. `Attachment` is the schema of
 * {@link AttachmentsController.AttachmentResponse}.
 */
public final class FollowupContracts {
    private FollowupContracts() { }

    // ---- Tasks (26, 13)
    public record Task(String id, String dogId, @Schema(description = "1–2000 characters") String text, TaskState state, Instant createdAt, Actor createdBy,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant doneAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "«feta per {article}{name} el {date}»") Actor doneBy,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with includeDeleted (ADMIN)") Instant deletedAt,
            List<AttachmentsController.AttachmentResponse> attachments, @Schema(description = "PATCH optimistic lock") long version) { }
    public record Actor(@Schema(requiredMode = NOT_REQUIRED, nullable = true) String accountId, AuthorRole role, String displayName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, allowableValues = {"MALE", "FEMALE", "OTHER"}, description = "Personal article of the member (personArticle)") String gender) { }
    public record TaskList(@Schema(description = "createdAt desc") List<Task> items) { }
    public record TaskCreateRequest(@NotBlank String dogId, @NotBlank @Size(max = 2000) String text,
            @Schema(requiredMode = NOT_REQUIRED, description = "fileKeys of TASK uploads, registered in the same transaction") List<@NotBlank String> attachmentIds) { }
    public record TaskPatchRequest(@NotBlank @Size(max = 2000) String text, @NotNull @PositiveOrZero Long version) { }

    // ---- Attachments
    public record AttachmentList(List<AttachmentsController.AttachmentResponse> items) { }
    /** `details` of the attachment errors (CATALEG_ERRORS §3 rule 2, R-10-11). */
    public record FileTooLargeDetails(@Schema(description = "files.maxSizeMb (DOG_PHOTO: files.dogPhotoMaxMb)") int maxSizeMb) { }
    public record AttachmentLimitReachedDetails(@Schema(description = "files.maxAttachmentsPerEntity") int max) { }

    // ---- Observations (R-10-12)
    public record ObservationsRequest(@NotNull @Size(max = 2000) @Schema(description = "Dog.remarks; empty clears it") String text,
            @NotNull @PositiveOrZero @Schema(description = "ObservationsBlock.version read by the caller") Long version) { }
    public record Observations(String text, Instant updatedAt, String updatedByName, long version) { }

    // ---- D14 (R-10-13)
    @Schema(description = "Universal list page (CONVENCIONS_API §4) of D14: unread first (activityAt desc), then the rest (activityAt desc)")
    public record FollowupPage(List<FollowupItem> items, @Schema(minimum = "0") int page, @Schema(minimum = "1") int size,
            @Schema(minimum = "0") long totalItems, @Schema(minimum = "0") int totalPages, List<Filter> appliedFilters) { }
    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record FollowupItem(String id, FollowupKind kind, @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only TASK") String taskId,
            String dogId, String dogName, @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null with levels.enabled = false") String levelCode,
            String memberId, String memberName, String authorName, AuthorRole authorRole, @Schema(description = "At most 120 characters") String textExcerpt,
            Instant createdAt, @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "«—» while pending") Instant completedAt,
            @Schema(description = "Task creation or last note change; completing does not move it") Instant activityAt, boolean unread) { }
    public record FollowupUnreadCount(int count) { }
}
