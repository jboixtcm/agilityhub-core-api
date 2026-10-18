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

    /**
     * S14 R-14-13 «mai el nom d'un tercer», fail-closed (E11-T01 round 2): every key that holds a name (`…Name`, `…_name`, any
     * case) is left out, unless it is one of these, which name the member, its own dogs and files, or things rather than people.
     * A staff name under a new key therefore never enters the package. `name` alone is a dog's, a file's or a catalog item's.
     */
    static final java.util.Set<String> KEPT_NAMES = java.util.Set.of("firstname", "fullname", "membername", "holdername", "dogname", "handlername",
            "filename", "clubname", "legalname", "levelname", "ringname", "planname", "activityname", "eventname", "locationname");
    /** Technical keys left out: the tenant, versions, leases and hashes of other systems. */
    static final java.util.Set<String> TECHNICAL = java.util.Set.of("_class", "clubId", "version", "claimToken", "leaseUntil", "heartbeatSequence",
            "ibanEncrypted", "nameKey", "lastEventId");

    /**
     * A stored document as a package row: `_id` → `id`, dates as ISO strings, without the technical keys or third parties' names;
     * nested maps and lists likewise. Inside an actor (a map under a `…By` key: `bookedBy`, `cancelledBy`, `markedBy`, `createdBy`…),
     * which may be a member of staff, no name or address is kept at all, only its ids and role.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> row(Map<String, ?> document) { return (Map<String, Object>) clean(document, false); }
    private static Object clean(Object value, boolean actor) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                String name = "_id".equals(key) ? "id" : String.valueOf(key);
                if (!TECHNICAL.contains(name) && !thirdParty(name, actor)) { result.put(name, clean(item, name.endsWith("By"))); }
            });
            return result;
        }
        if (value instanceof java.util.Collection<?> list) { return list.stream().map(item -> clean(item, actor)).toList(); }
        if (value instanceof java.util.Date date) { return date.toInstant().toString(); }
        if (value instanceof java.time.temporal.TemporalAccessor || value instanceof org.bson.types.ObjectId || value instanceof Enum<?>) { return value.toString(); }
        if (value instanceof org.bson.types.Decimal128 decimal) { return decimal.bigDecimalValue(); }
        return value;
    }
    /** Whether a key may name somebody else: a name key not kept, or, inside an actor, any name or e-mail. */
    static boolean thirdParty(String key, boolean actor) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        boolean nameKey = lower.endsWith("name") && !lower.equals("name");
        if (actor) { return lower.endsWith("name") || lower.contains("email"); }
        return nameKey && !KEPT_NAMES.contains(lower.replace("_", ""));
    }
    /** «···· 2231»: the IBAN of the package is masked (S14 §13-5); the encrypted copy never leaves. */
    public static String maskIban(Object iban) {
        if (iban == null) { return null; }
        String digits = iban.toString().replace(" ", "");
        return digits.length() <= 4 ? "····" : "···· " + digits.substring(digits.length() - 4);
    }
}
