package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.platform.application.PublicClubAccess;
import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Service;

/** Read-only resource guards for the S07 contract, without lifecycle side effects. */
@Service
public class ActivityContractAccess {
    private final ActivityRepository activities;
    private final ActivityRegistrationRepository registrations;
    private final PublicClubAccess publicClubs;
    private final ModuleGuard modules;
    public ActivityContractAccess(ActivityRepository activities, ActivityRegistrationRepository registrations,
            PublicClubAccess publicClubs, ModuleGuard modules) {
        this.activities = activities; this.registrations = registrations; this.publicClubs = publicClubs; this.modules = modules;
    }
    public void tenant() { TenantContext.require(); }
    public void activity(String id) { activities.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public void document(String id, String docId) {
        var activity = activities.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.documents().stream().noneMatch(d -> d.id().equals(docId))) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    public void registration(String id, String memberId, boolean staff) {
        var registration = registrations.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!staff && !registration.memberId().equals(memberId)) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    public void waitlist(Boolean joinWaitlist) {
        if (Boolean.TRUE.equals(joinWaitlist)) { modules.require(TenantContext.require(), Module.WAITLIST); }
    }
    public void publicRead(String clubSlug, String key, boolean needsKey, String slug, String fileId) {
        var club = publicClubs.resolveClub(clubSlug);
        modules.require(club.club().id(), Module.ACTIVITIES);
        if (needsKey) { publicClubs.resolve(clubSlug, key); }
        if (slug != null) {
            try (var scope = TenantContext.open(club.club().id())) {
                var activity = activities.findBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
                if (activity.state() != com.agilityhub.core.clubs.activities.domain.ActivityState.PUBLISHED
                        && activity.state() != com.agilityhub.core.clubs.activities.domain.ActivityState.FINISHED
                        && activity.state() != com.agilityhub.core.clubs.activities.domain.ActivityState.CANCELLED) {
                    throw new ApiException(ErrorCode.NOT_FOUND);
                }
                if (fileId != null && (activity.image() == null || !fileId.equals(activity.image().fileId()))
                        && activity.documents().stream().noneMatch(d -> fileId.equals(d.id()))) {
                    throw new ApiException(ErrorCode.NOT_FOUND);
                }
            }
        }
    }
}
