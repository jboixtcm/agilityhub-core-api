package com.agilityhub.core.clubs.activities.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.*;
import java.util.List;
import com.agilityhub.core.clubs.activities.domain.*;

@Document("activity_registrations")
public record ActivityRegistration(@Id String id, String clubId,
        String activityId, String memberId, RegistrationState state, RegistrationOrigin origin, Instant registeredAt,
        RegisteredBy registeredBy, Integer position, Instant promotedAt, Instant cancelledAt, CancelledBy cancelledBy,
        RegistrationCancelReason cancelReason, Instant activityStartsAt, String upfrontPaymentId,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {
    public record RegisteredBy(String accountId, String impersonatedMemberId, String displayName) { }
    public record CancelledBy(String accountId, CancelledByRole role) { }
    public enum CancelledByRole { MEMBER, ADMIN, SYSTEM }
}
