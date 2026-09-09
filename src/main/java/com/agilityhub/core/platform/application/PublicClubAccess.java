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
    public PublicClubAccess(ClubRepository clubs, ClubConfigService configs) { this.clubs = clubs; this.configs = configs; }
    public ClubConfig resolve(String slug, String key) {
        var club = clubs.findBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        if (key == null || key.isBlank() || club.publicApiKeyHash() == null || !MessageDigest.isEqual(
                digest(key).getBytes(StandardCharsets.US_ASCII), club.publicApiKeyHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(ErrorCode.INVALID_API_KEY);
        }
        if (club.status() == com.agilityhub.core.platform.persistence.Club.Status.SUSPENDED) { throw new ApiException(ErrorCode.CLUB_SUSPENDED); }
        return configs.get(club.id());
    }
    public static String digest(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
