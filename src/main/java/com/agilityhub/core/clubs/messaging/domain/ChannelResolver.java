package com.agilityhub.core.clubs.messaging.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.agilityhub.core.clubs.messaging.domain.DeliveryStatus.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationChannel.*;

/**
 * S11 R-11-03, the channel truth table, literally: for one recipient of one audience, the deliveries a notification is born
 * with and their initial status. A matrix cell that is off (or outside the code's caps) gives no delivery at all; the
 * notification is still stored (R-11-10). Preferences only concern `MEMBER` and never a `SYSTEM` category; they can remove
 * EMAIL (per category) and the PUSH of `CLUB_NEWS` and of every announcement ({@link #pushClubNews}), never APP nor SMS
 * («+SMS» is fixed, Josep 18-08). `APPLICANT` is EMAIL
 * only. The monthly SMS cap is applied by the dispatcher right before each send (R-11-06, S11 §6 `dispatch`), see
 * {@link #capReached}.
 */
public final class ChannelResolver {
    private ChannelResolver() { }

    /** One contact address of the recipient; a bounced one is never written to (R-11-08). */
    public record EmailAddress(String address, boolean bounced) {
        public EmailAddress { Objects.requireNonNull(address); }
    }
    /**
     * What the resolver needs to know about the recipient: the account (APP), every contact e-mail, every phone in E.164,
     * the ids of the `ACTIVE` push subscriptions of the account, and the member's preferences (defaults for staff and
     * applicants, who ignore them anyway).
     */
    public record Contact(String accountId, List<EmailAddress> emails, List<String> phones, List<String> pushSubscriptions,
            NotificationPreference preferences) {
        public Contact {
            emails = emails == null ? List.of() : List.copyOf(emails);
            phones = phones == null ? List.of() : List.copyOf(new LinkedHashSet<>(phones));
            pushSubscriptions = pushSubscriptions == null ? List.of() : List.copyOf(pushSubscriptions);
            preferences = preferences == null ? NotificationPreference.DEFAULTS : preferences;
        }
    }
    /** The club's `SMS` and `PUSH` modules (R-11-17). */
    public record Modules(boolean sms, boolean push) { }
    /** A delivery to create: channel, destination (account, address, phone or subscription id; `null` when skipped without one) and status. */
    public record Planned(NotificationChannel channel, String target, DeliveryStatus status) { }

    /**
     * @param matrixRow the template's row for the audience (`MessageTemplate.matrix[audience]`); ignored for `APPLICANT`,
     *                  whose EMAIL is fixed by the code
     */
    public static List<Planned> resolve(NotificationSpec spec, NotificationAudience audience, Map<NotificationChannel, Boolean> matrixRow,
            Modules modules, Contact contact) {
        Objects.requireNonNull(spec); Objects.requireNonNull(audience); Objects.requireNonNull(modules); Objects.requireNonNull(contact);
        var planned = new ArrayList<Planned>();
        if (!spec.audiences().contains(audience)) { return planned; }
        if (audience == APPLICANT) {
            email(planned, contact);
            // S04 §8 (N-01, N-03): an existing member who applies (add-dog) also gets the APP copy in their account.
            if (contact.accountId() != null) { planned.add(new Planned(APP, contact.accountId(), DELIVERED)); }
            return planned;
        }
        var row = matrixRow == null ? Map.<NotificationChannel, Boolean>of() : matrixRow;
        var caps = spec.caps(audience);
        boolean member = audience == MEMBER, preferencesApply = member && spec.category().templated();
        if (on(row, caps, APP)) {
            planned.add(contact.accountId() == null ? new Planned(APP, null, SKIPPED_NO_CONTACT) : new Planned(APP, contact.accountId(), DELIVERED));
        }
        if (on(row, caps, EMAIL)) {
            if (preferencesApply && !contact.preferences().email(spec.category())) { planned.add(new Planned(EMAIL, null, SKIPPED_BY_PREFERENCE)); }
            else { email(planned, contact); }
        }
        if (on(row, caps, SMS)) {
            if (!modules.sms()) { planned.add(new Planned(SMS, null, SKIPPED_MODULE_OFF)); }
            else if (contact.phones().isEmpty()) { planned.add(new Planned(SMS, null, SKIPPED_NO_CONTACT)); }
            else { contact.phones().forEach(phone -> planned.add(new Planned(SMS, phone, QUEUED))); }
        }
        if (spec.push().contains(audience)) {
            if (!modules.push()) { planned.add(new Planned(PUSH, null, SKIPPED_MODULE_OFF)); }
            else if (preferencesApply && pushClubNews(spec) && !contact.preferences().pushClubNews()) {
                planned.add(new Planned(PUSH, null, SKIPPED_BY_PREFERENCE));
            } else if (contact.pushSubscriptions().isEmpty()) { planned.add(new Planned(PUSH, null, SKIPPED_NO_CONTACT)); }
            else { contact.pushSubscriptions().forEach(id -> planned.add(new Planned(PUSH, id, QUEUED))); }
        }
        return planned;
    }

    /**
     * R-11-06 at the cap: the SMS of the recipient become `SKIPPED_CAP` and an EMAIL is forced to every address that is not
     * bounced and has no live EMAIL delivery yet, even when the preference is off: one per address compared without case, as
     * the first resolution writes them. Returns the EMAIL deliveries to add.
     */
    public static List<Planned> capReached(Contact contact, java.util.Collection<String> liveEmailTargets) {
        var live = new java.util.HashSet<String>();
        liveEmailTargets.forEach(target -> live.add(target.strip().toLowerCase(java.util.Locale.ROOT)));
        var targets = new java.util.LinkedHashMap<String, String>();
        for (var address : contact.emails()) {
            String key = address.address().strip().toLowerCase(java.util.Locale.ROOT);
            if (!address.bounced() && !key.isEmpty() && !live.contains(key)) { targets.putIfAbsent(key, address.address().strip()); }
        }
        var forced = new ArrayList<Planned>();
        targets.values().forEach(target -> forced.add(new Planned(EMAIL, target, QUEUED)));
        return forced;
    }

    /**
     * The PUSH a member turns off with `pushClubNews`: a `CLUB_NEWS` notice, and every announcement (N-24, also sent with a
     * `CUSTOM` template of another category) whatever its category (S11 R-11-13, ruling E82).
     */
    static boolean pushClubNews(NotificationSpec spec) {
        return spec.category() == NotificationCategory.CLUB_NEWS || NotificationCatalog.ANNOUNCEMENT.equals(spec.code());
    }

    private static boolean on(Map<NotificationChannel, Boolean> row, java.util.Set<NotificationChannel> caps, NotificationChannel channel) {
        return caps.contains(channel) && Boolean.TRUE.equals(row.get(channel));
    }
    /**
     * `QUEUED` to every address that is not bounced (one delivery per address, compared without case); `SKIPPED_NO_CONTACT`
     * when there is none (all bounced, or no address).
     */
    private static void email(List<Planned> planned, Contact contact) {
        var targets = new java.util.LinkedHashMap<String, String>();
        for (var address : contact.emails()) {
            if (!address.bounced() && !address.address().isBlank()) { targets.putIfAbsent(address.address().strip().toLowerCase(java.util.Locale.ROOT), address.address().strip()); }
        }
        if (targets.isEmpty()) { planned.add(new Planned(EMAIL, null, SKIPPED_NO_CONTACT)); return; }
        targets.values().forEach(target -> planned.add(new Planned(EMAIL, target, QUEUED)));
    }
}
