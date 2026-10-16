package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S17 R-17-11 (E10-T04): `POST /platform/clubs/{id}/public-api-key` generates the club's website key (`agh_pub_…`), answers it
 * once and stores only its SHA-256 digest. The key it replaces keeps opening the public routes (S05/S07 `X-Api-Key`) for
 * {@link #OVERLAP}, then stops; a second rotation inside that window ends the older key at once. Audited
 * `PUBLIC_API_KEY_ROTATED` without any key value; publishes `ClubUpdated`.
 */
@Service
public class PlatformPublicKeys {
    public static final String PREFIX = "agh_pub_";
    public static final Duration OVERLAP = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** `key` is answered once and never audited, logged or stored; `previousValidUntil` is null for a club's first key. */
    public record Rotation(String id, @AuditField Instant previousValidUntil, String key) {
        @Override public String toString() { return "Rotation[redacted]"; }
    }
    private final ClubRepository clubs; private final PlatformClubChanges changes; private final Clock clock;
    public PlatformPublicKeys(ClubRepository clubs, PlatformClubChanges changes, Clock clock) { this.clubs = clubs; this.changes = changes; this.clock = clock; }

    @Transactional
    @Audited(action = AuditAction.PUBLIC_API_KEY_ROTATED, entityType = "'Club'", entity = "#result.id", reason = "'Public API key rotation'",
            details = "#result.previousValidUntil == null ? null : T(java.util.Map).of('previousValidUntil', #result.previousValidUntil.toString())")
    public Rotation rotate() {
        var club = changes.club(); Instant now = clock.instant();
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String previous = club.publicApiKeyHash(); Instant until = previous == null ? null : now.plus(OVERLAP);
        clubs.rotatePublicApiKey(club.id(), club.version(), PublicClubAccess.digest(key), previous, until, now);
        changes.publish("ClubUpdated", club.id(), Map.of("publicApiKey", "rotated"));
        return new Rotation(club.id(), until, key);
    }
}
