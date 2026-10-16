package com.agilityhub.core.platform.application.privacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The contents of a member's data package as the sources fill it: `data.json`'s sections and the files of `fitxers/`. */
public final class MemberDataPackage {
    public static final String SCHEMA = "memberData.v1";
    /** The sections of `memberData.v1`, in the order S14 R-13 lists them (a missing source leaves its section empty). */
    public static final List<String> SECTIONS = List.of("account", "memberships", "member", "consents", "notificationPreferences", "dogs", "bookings",
            "trainingBookings", "waitlistEntries", "attendances", "tasks", "inactivityPeriods", "leaveRequests", "invoices", "upfrontPayments",
            "packBalances", "notifications", "auditEntries", "familyGroup");
    private final Map<String, Object> sections = new LinkedHashMap<>();
    private final List<File> files = new ArrayList<>();
    public MemberDataPackage() { SECTIONS.forEach(name -> sections.put(name, name.equals("account") || name.equals("member") || name.equals("familyGroup")
            || name.equals("notificationPreferences") ? null : new ArrayList<>())); }

    public void section(String name, Object value) {
        if (!SECTIONS.contains(name)) { throw new IllegalArgumentException("Not a memberData.v1 section: " + name); }
        sections.put(name, value);
    }
    /** A file under `fitxers/` (`path` relative to it), read from its store when the package is written. */
    public void file(String store, String key, String path) { if (key != null) { files.add(new File(store, key, path)); } }

    public Map<String, Object> sections() { return Collections.unmodifiableMap(sections); }
    public List<File> files() { return List.copyOf(files); }
    public record File(String store, String key, String path) { }

    /** Names of third parties (staff who acted on the member's data) never enter the package (S14 R-14-13). */
    static final java.util.Set<String> THIRD_PARTY = java.util.Set.of("displayName", "actorName", "authorName", "updatedByName", "savedByName",
            "impersonatedName", "markedByName");
    /** Technical keys left out: the tenant, versions, leases and hashes of other systems. */
    static final java.util.Set<String> TECHNICAL = java.util.Set.of("_class", "clubId", "version", "claimToken", "leaseUntil", "heartbeatSequence",
            "ibanEncrypted", "nameKey", "lastEventId");

    /**
     * A stored document as a package row: `_id` → `id`, dates as ISO strings, without the technical keys or third parties' names;
     * nested maps and lists likewise.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> row(Map<String, ?> document) { return (Map<String, Object>) clean(document); }
    private static Object clean(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                String name = "_id".equals(key) ? "id" : String.valueOf(key);
                if (!TECHNICAL.contains(name) && !THIRD_PARTY.contains(name)) { result.put(name, clean(item)); }
            });
            return result;
        }
        if (value instanceof java.util.Collection<?> list) { return list.stream().map(MemberDataPackage::clean).toList(); }
        if (value instanceof java.util.Date date) { return date.toInstant().toString(); }
        if (value instanceof java.time.temporal.TemporalAccessor || value instanceof org.bson.types.ObjectId || value instanceof Enum<?>) { return value.toString(); }
        if (value instanceof org.bson.types.Decimal128 decimal) { return decimal.bigDecimalValue(); }
        return value;
    }
    /** «···· 2231»: the IBAN of the package is masked (S14 §13-5); the encrypted copy never leaves. */
    public static String maskIban(Object iban) {
        if (iban == null) { return null; }
        String digits = iban.toString().replace(" ", "");
        return digits.length() <= 4 ? "····" : "···· " + digits.substring(digits.length() - 4);
    }
}
