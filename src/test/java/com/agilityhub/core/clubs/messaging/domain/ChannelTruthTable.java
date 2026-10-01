package com.agilityhub.core.clubs.messaging.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationChannel.*;

/**
 * S11 R-11-03, the channel truth table as test data (T-11-02, PLA_BACKEND §9.8): every catalog code × every audience of the
 * code × every row of the table — the template cell (seed matrix, all on, all off), the member's preference (e-mail of the
 * code's category ON/OFF, `pushClubNews` ON/OFF), the modules (`SMS`, `PUSH` on/off) and the contact (present, absent, every
 * address bounced) — with the deliveries the table says. Public so the E7-T04 matrix test (channel × audience × preference
 * over the catalog) reuses the same provider.
 *
 * <p>The expected deliveries are written from the table's rows, one {@code switch} arm per row of R-11-03, not from the
 * resolver's code: APP → `DELIVERED` (feed; no account = no feed); EMAIL → `SKIPPED_BY_PREFERENCE` only for `MEMBER`, else
 * `QUEUED` to every address that did not bounce, else `SKIPPED_NO_CONTACT`; SMS → `SKIPPED_MODULE_OFF`, else `QUEUED` per
 * phone, else `SKIPPED_NO_CONTACT`; PUSH (fixed by the code, never by the template) → `SKIPPED_MODULE_OFF`, else
 * `SKIPPED_BY_PREFERENCE` for a `MEMBER` `CLUB_NEWS` notice without `pushClubNews`, else `QUEUED` per active subscription,
 * else `SKIPPED_NO_CONTACT`; a cell that is off (or outside the code's caps) → no delivery; `APPLICANT` → EMAIL only (plus the
 * S04 §8 feed copy of an existing member who applies).</p>
 */
public final class ChannelTruthTable {
    private ChannelTruthTable() { }

    /** The three template cells of the table's «Plantilla» column: the seed, every cell on (caps still apply), every cell off. */
    public enum Cells { SEED, ALL_ON, ALL_OFF }
    /** The «Contacte» column: everything present, nothing at all, only bounced addresses (phones and subscriptions kept). */
    public enum ContactKind { PRESENT, ABSENT, ALL_BOUNCED }

    /** One row of the table for one code and audience, with the deliveries it must produce (order-insensitive). */
    public record Case(NotificationSpec spec, NotificationAudience audience, Cells cells, boolean emailPreference, boolean pushClubNews, boolean smsModule,
            boolean pushModule, ContactKind contactKind, Map<NotificationChannel, Boolean> matrixRow, ChannelResolver.Modules modules,
            ChannelResolver.Contact contact, List<ChannelResolver.Planned> expected) {
        @Override public String toString() {
            return spec.code() + " " + audience + " cells=" + cells + " email=" + emailPreference + " pushNews=" + pushClubNews + " sms=" + smsModule
                    + " push=" + pushModule + " contact=" + contactKind;
        }
    }

    public static final String ACCOUNT = "account-laura";
    public static final List<String> EMAILS = List.of("laura@example.test", "laura.work@example.test");
    public static final List<String> PHONES = List.of("+34600000001", "+34600000002");
    public static final List<String> SUBSCRIPTIONS = List.of("subscription-phone", "subscription-laptop");

    /** Every catalog code (R1 and LATER, templated and SYSTEM): the resolver answers for all of them. */
    public static Stream<NotificationSpec> codes() { return NotificationCatalog.specs().stream(); }

    /** Every row of the table for every audience of the code. */
    public static List<Case> rows(NotificationSpec spec) {
        var cases = new ArrayList<Case>();
        for (var audience : spec.audiences()) {
            for (var cells : Cells.values()) {
                for (boolean email : new boolean[] {true, false}) {
                    for (boolean pushNews : new boolean[] {true, false}) {
                        for (boolean sms : new boolean[] {true, false}) {
                            for (boolean push : new boolean[] {true, false}) {
                                for (var kind : ContactKind.values()) { cases.add(row(spec, audience, cells, email, pushNews, sms, push, kind)); }
                            }
                        }
                    }
                }
            }
        }
        return cases;
    }

    public static Case row(NotificationSpec spec, NotificationAudience audience, Cells cells, boolean email, boolean pushNews, boolean sms, boolean push,
            ContactKind kind) {
        var matrix = matrix(spec, audience, cells);
        var preferences = preferences(spec.category(), email, pushNews);
        var contact = contact(kind, preferences);
        var modules = new ChannelResolver.Modules(sms, push);
        return new Case(spec, audience, cells, email, pushNews, sms, push, kind, matrix, modules, contact,
                expected(spec, audience, matrix, modules, contact, email, pushNews, kind));
    }

    static Map<NotificationChannel, Boolean> matrix(NotificationSpec spec, NotificationAudience audience, Cells cells) {
        var row = new EnumMap<NotificationChannel, Boolean>(NotificationChannel.class);
        for (var channel : List.of(APP, EMAIL, SMS)) {
            row.put(channel, switch (cells) {
                case SEED -> spec.defaultMatrix().getOrDefault(audience, Map.of()).getOrDefault(channel, false);
                case ALL_ON -> true;
                case ALL_OFF -> false;
            });
        }
        return row;
    }
    /** The preference block with the code's category e-mail switched as asked; the other categories keep the product defaults. */
    static NotificationPreference preferences(NotificationCategory category, boolean email, boolean pushNews) {
        var byCategory = new EnumMap<NotificationCategory, Boolean>(NotificationCategory.class);
        if (category.templated()) { byCategory.put(category, email); }
        return new NotificationPreference(byCategory, null, pushNews, null, null);
    }
    static ChannelResolver.Contact contact(ContactKind kind, NotificationPreference preferences) {
        return switch (kind) {
            case PRESENT -> new ChannelResolver.Contact(ACCOUNT, EMAILS.stream().map(e -> new ChannelResolver.EmailAddress(e, false)).toList(), PHONES, SUBSCRIPTIONS,
                    preferences);
            case ABSENT -> new ChannelResolver.Contact(null, List.of(), List.of(), List.of(), preferences);
            case ALL_BOUNCED -> new ChannelResolver.Contact(ACCOUNT, EMAILS.stream().map(e -> new ChannelResolver.EmailAddress(e, true)).toList(), PHONES,
                    SUBSCRIPTIONS, preferences);
        };
    }

    /** The deliveries of R-11-03, row by row of the table (public for E7-T04's engine matrix, `NotificationMatrixTest`). */
    public static List<ChannelResolver.Planned> expected(NotificationSpec spec, NotificationAudience audience, Map<NotificationChannel, Boolean> matrix,
            ChannelResolver.Modules modules, ChannelResolver.Contact contact, boolean emailPreference, boolean pushClubNews, ContactKind kind) {
        var out = new ArrayList<ChannelResolver.Planned>();
        if (!spec.audiences().contains(audience)) { return out; }
        boolean hasAccount = kind != ContactKind.ABSENT, hasAddress = kind == ContactKind.PRESENT, hasPhones = kind != ContactKind.ABSENT;
        if (audience == APPLICANT) {
            // «APPLICANT | EMAIL | fix | — | — | signup.email | QUEUED»; S04 §8: an existing member who applies also reads it in the app.
            if (hasAddress) { EMAILS.forEach(e -> out.add(new ChannelResolver.Planned(EMAIL, e, QUEUED))); }
            else { out.add(new ChannelResolver.Planned(EMAIL, null, SKIPPED_NO_CONTACT)); }
            if (hasAccount) { out.add(new ChannelResolver.Planned(APP, ACCOUNT, DELIVERED)); }
            return out;
        }
        boolean member = audience == MEMBER, templated = spec.category() != NotificationCategory.SYSTEM;
        for (var channel : NotificationChannel.values()) {
            // «qualsevol | qualsevol | ✗»: a cell off, or outside the caps (R-11-12), is no delivery; PUSH is the code's.
            boolean cell = channel == PUSH ? spec.push().contains(audience) : spec.caps(audience).contains(channel) && matrix.get(channel);
            if (!cell) { continue; }
            switch (channel) {
                // «MEMBER | APP | ✓ | — | sempre | compte | DELIVERED (feed)» (and the staff rows «com MEMBER sense preferència»).
                case APP -> out.add(hasAccount ? new ChannelResolver.Planned(APP, ACCOUNT, DELIVERED) : new ChannelResolver.Planned(APP, null, SKIPPED_NO_CONTACT));
                case EMAIL -> {
                    if (member && templated && !emailPreference) { out.add(new ChannelResolver.Planned(EMAIL, null, SKIPPED_BY_PREFERENCE)); }  // «= false»
                    else if (hasAddress) { EMAILS.forEach(e -> out.add(new ChannelResolver.Planned(EMAIL, e, QUEUED))); }                    // «≥ 1 email no bounced»
                    else { out.add(new ChannelResolver.Planned(EMAIL, null, SKIPPED_NO_CONTACT)); }                                          // «tots rebotats»
                }
                case SMS -> {
                    if (!modules.sms()) { out.add(new ChannelResolver.Planned(SMS, null, SKIPPED_MODULE_OFF)); }                             // R-11-17
                    else if (hasPhones) { PHONES.forEach(p -> out.add(new ChannelResolver.Planned(SMS, p, QUEUED))); }                       // «+SMS» fixed
                    else { out.add(new ChannelResolver.Planned(SMS, null, SKIPPED_NO_CONTACT)); }
                }
                case PUSH -> {
                    if (!modules.push()) { out.add(new ChannelResolver.Planned(PUSH, null, SKIPPED_MODULE_OFF)); }
                    else if (member && spec.category() == NotificationCategory.CLUB_NEWS && !pushClubNews) { out.add(new ChannelResolver.Planned(PUSH, null, SKIPPED_BY_PREFERENCE)); }
                    else if (hasAccount) { SUBSCRIPTIONS.forEach(s -> out.add(new ChannelResolver.Planned(PUSH, s, QUEUED))); }
                    else { out.add(new ChannelResolver.Planned(PUSH, null, SKIPPED_NO_CONTACT)); }
                }
            }
        }
        return out;
    }

    /** Per code: how many rows the table has (the T-11-02 report). */
    public static Map<String, Integer> counts() {
        var counts = new LinkedHashMap<String, Integer>();
        codes().forEach(spec -> counts.put(spec.code(), rows(spec).size()));
        return counts;
    }
}
