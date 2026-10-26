package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.platform.application.privacy.MemberConsents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S14 R-14-18 over S04's append-only consent ledger (`Member.consents`, read as `{history, privacyPolicy, imageRights}`). A member
 * stored before the ledger (S03's two objects) shows those two entries; a value S03 did not keep is the club's default locale and
 * the source `LEGACY`. The history survives the erasure (R-14-15 keeps `consents[]`).
 */
@Component
public class CensusConsents implements MemberConsents {
    private final CensusRepository<Member> members;
    private final CensusAccess access;
    public CensusConsents(CensusRepository<Member> members, CensusAccess access) { this.members = members; this.access = access; }

    @Override @SuppressWarnings("unchecked")
    public List<Entry> history(String memberId) {
        var member = members.require(memberId);
        var ledger = member.consents == null ? Map.<String, Object>of() : member.consents;
        var rows = new ArrayList<Map<String, Object>>();
        if (ledger.get("history") instanceof List<?> history) { history.forEach(row -> rows.add((Map<String, Object>) row)); }
        else {
            for (String key : List.of("privacyPolicy", "imageRights")) {
                if (ledger.get(key) instanceof Map<?, ?> row) {
                    var copy = new java.util.LinkedHashMap<String, Object>((Map<String, Object>) row);
                    copy.putIfAbsent("type", key.equals("privacyPolicy") ? "PRIVACY_POLICY" : "IMAGE_USE");
                    if (key.equals("privacyPolicy")) { copy.put("granted", com.agilityhub.core.shared.domain.ConsentGrant.legacyPrivacy(row)); }
                    rows.add(copy);
                }
            }
        }
        String locale = access.config().club().defaultLocale();
        return rows.stream().map(row -> new Entry(text(row.get("type")), com.agilityhub.core.shared.domain.ConsentGrant.granted(row), text(row.get("version")),
                instant(row.get("acceptedAt") != null ? row.get("acceptedAt") : row.get("at")), row.get("locale") == null ? locale : text(row.get("locale")),
                row.get("ipHash") == null ? null : text(row.get("ipHash")), row.get("source") == null ? "LEGACY" : text(row.get("source"))))
                .sorted(Comparator.comparing(Entry::at, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static Instant instant(Object value) {
        if (value instanceof Instant instant) { return instant; }
        if (value instanceof java.util.Date date) { return date.toInstant(); }
        return value == null ? null : Instant.parse(value.toString());
    }
}
