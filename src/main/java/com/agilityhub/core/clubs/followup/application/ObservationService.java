package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S10 R-10-12 observations: `Dog.remarks`, the single private field of the instructors and admins. The census owns the
 * `Dog` document, so the write goes through {@link FollowupCensusAccess#saveRemarks} (`DogService`: `DogUpdated{diff:
 * remarks}`, `AuditEntry{DOG_UPDATED}`, STALE_VERSION, READMISSION_PENDING). No notification and no D14 row.
 */
@Service
public class ObservationService {
    private final FollowupCensusAccess census;
    public ObservationService(FollowupCensusAccess census) { this.census = census; }

    @Transactional
    public FollowupCensusAccess.Remarks save(String dogId, String text, long version, Task.Actor by) {
        return census.saveRemarks(dogId, text, version, by.displayName());
    }
}
