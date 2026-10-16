package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.util.List;

/**
 * S14 R-14-18 `GET /members/{id}/consents`: a member's full consent history in the current club, oldest first, as S04 writes it
 * (implemented by `clubs.census`; another club's or an unknown member → `404`).
 */
public interface MemberConsents {
    List<Entry> history(String memberId);
    record Entry(String type, boolean granted, String version, Instant at, String locale, String ipHash, String source) { }
}
