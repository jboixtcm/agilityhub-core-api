package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.persistence.FollowupErasureRepository;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import com.agilityhub.core.platform.application.privacy.PrivacyFileStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;

/**
 * S14 WP-14-D, S10 follow-up: R-14-15's `Task` (`text → «(suprimit)»`, attachments deleted) and the dogs' note attachments. It is
 * also the store of the members' files (dog photos and documents, attachments; `attachments:` in `filesPending[]`), for the
 * package's `fitxers/` (R-14-13) and the cleanup (S14 §7 `ErasureFilesCleanup`).
 */
@Component
public class FollowupErasure implements ErasureStep, PrivacyFileStore, MemberDataSource {
    public static final String STORE = "attachments";
    private final FollowupErasureRepository erasure;
    private final AttachmentStorage storage;
    public FollowupErasure(FollowupErasureRepository erasure, AttachmentStorage storage) { this.erasure = erasure; this.storage = storage; }

    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        var tasks = erasure.taskIds(subject.memberIds(), subject.dogIds());
        effect.count("tasks", erasure.eraseTasks(tasks, subject.accountIds(), subject.placeholders().task(), subject.at()));
        var keys = new LinkedHashSet<String>(erasure.eraseAttachments(tasks, subject.dogIds(), subject.at()));
        keys.addAll(erasure.deleteGrants(subject.dogIds(), tasks, subject.erasedAccountIds()));
        keys.forEach(key -> effect.file(STORE, key));
        effect.count("followup_items", erasure.eraseItems(subject.memberIds(), subject.dogIds(), subject.accountIds(), subject.placeholders().task()));
        return effect.build();
    }

    @Override public String store() { return STORE; }
    @Override public InputStream read(String key) {
        try { return new ByteArrayInputStream(storage.read(key)); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @Override public void delete(String key) { storage.delete(key); }

    /** S14 R-14-13: `tasks[]` (their attachments listed by name) and the attachments' files under `fitxers/adjunts/`. */
    @Override public void contribute(ErasureMember member, MemberDataPackage data) {
        var tasks = erasure.tasks(member.id(), member.dogIds());
        var attachments = erasure.attachments(tasks.stream().map(task -> task.getString("_id")).toList(), member.dogIds());
        data.section("tasks", tasks.stream().map(task -> {
            var row = MemberDataPackage.row(task);
            row.put("attachments", attachments.stream().filter(a -> "TASK".equals(a.getString("entityType")) && task.getString("_id").equals(a.getString("entityId")))
                    .map(a -> a.getString("name")).toList());
            return (Object) row;
        }).toList());
        attachments.forEach(a -> data.file(STORE, a.getString("fileKey"), "adjunts/" + a.getString("_id") + "-" + (a.getString("name") == null ? "fitxer" : a.getString("name"))));
    }
}
