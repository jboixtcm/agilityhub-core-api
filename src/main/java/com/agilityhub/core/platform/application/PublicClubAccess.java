package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/** Public website keys are stored only as SHA-256 digests; provisioning/rotation belongs to club administration. */
@Service
public class PublicClubAccess {
    private final ClubRepository clubs;
    private final ClubConfigService configs;
    private final java.time.Clock clock;
    public PublicClubAccess(ClubRepository clubs, ClubConfigService configs, java.time.Clock clock) { this.clubs = clubs; this.configs = configs; this.clock = clock; }
    /**
     * The keyed public routes (S05 R-05-21 plans, S05 §6 club pages, S07 R-07-12 activities): the key is checked first,
     * so without a valid key the answer does not say whether the slug exists — an unknown slug is 403 INVALID_API_KEY
     * too, never 404 CLUB_NOT_FOUND (E5-T11). A suspended club answers CLUB_SUSPENDED only to its own key. The timing
     * still differs slightly: an unknown slug returns before the SHA-256 digest that a known slug computes. That is
     * accepted, because club slugs are public anyway (they are in every club URL), so it reveals nothing (E5-T14).
     */
    public ClubConfig resolve(String slug, String key) {
        if (key == null || key.isBlank()) { throw new ApiException(ErrorCode.INVALID_API_KEY); }
        var club = clubs.findBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.INVALID_API_KEY));
        // S17 R-17-11 (E10-T04): the key a rotation replaced stays valid until the end of its 24-hour overlap.
        String presented = digest(key);
        boolean previous = club.publicApiKeyPreviousUntil() != null && clock.instant().isBefore(club.publicApiKeyPreviousUntil())
                && matches(presented, club.publicApiKeyPreviousHash());
        if (!matches(presented, club.publicApiKeyHash()) && !previous) {
            throw new ApiException(ErrorCode.INVALID_API_KEY);
        }
        if (club.status() == com.agilityhub.core.platform.persistence.Club.Status.SUSPENDED) { throw new ApiException(ErrorCode.CLUB_SUSPENDED); }
        return configs.get(club.id());
    }
    /** Resolve a public club before module guards, including keyless published-file routes. */
    public ClubConfig resolveClub(String slug) {
        var club = clubs.findBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        if (club.status() == com.agilityhub.core.platform.persistence.Club.Status.SUSPENDED) { throw new ApiException(ErrorCode.CLUB_SUSPENDED); }
        return configs.get(club.id());
    }
    public String websiteUrl(String clubId) {
        return clubs.findById(clubId).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND)).websiteUrl();
    }
    private static boolean matches(String presented, String stored) {
        return stored != null && MessageDigest.isEqual(presented.getBytes(StandardCharsets.US_ASCII), stored.getBytes(StandardCharsets.US_ASCII));
    }
    public static String digest(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
