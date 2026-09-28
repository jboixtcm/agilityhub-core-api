package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.domain.FollowupKind;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S10 §3 D14 projection: one row per task, one MEMBER_NOTE row per dog (updated on every change). Member and dog
 * names and the level code are resolved on read; the author (`authorAccountId`, `authorName`, `authorGender`) is whoever
 * wrote the task or the note, stored as they were then, never the dog's current owner (E64); `textExcerpt` ≤ 120;
 * completing a task sets `completedAt`, never `activityAt` (R-10-13); `hidden` = the task was deleted. `lastEventId` is the
 * id of the last `MemberNoteChanged` applied to a MEMBER_NOTE row, so the same event consumed again changes nothing (S10 §7,
 * E6-T03 round 4); null on task rows and on note rows written before it.
 */
@Document("followup_items")
public record FollowupItem(@Id String id, String clubId, FollowupKind kind, String taskId, String dogId, String memberId,
        String authorAccountId, AuthorRole authorRole, String authorName, String authorGender, String textExcerpt, Instant createdAt, Instant completedAt,
        Instant activityAt, boolean hidden, Instant updatedAt, String lastEventId) implements TenantEntity { }
