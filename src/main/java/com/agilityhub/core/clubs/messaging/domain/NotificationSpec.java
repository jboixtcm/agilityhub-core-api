package com.agilityhub.core.clubs.messaging.domain;

import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One row of `CATALEG_NOTIFICACIONS.md` (main table or Annex A) as product code (S11 §3 «NotificationCatalog», WP-11-A):
 * the only event → code table of the product.
 *
 * @param eventTypes    the `CATALEG_ESDEVENIMENTS` events that emit the code; empty for a code another service triggers directly
 * @param audiences     R-11-02 audiences of the code, in the document's order
 * @param caps          per audience, the channels a template may activate (R-11-12), `PUSH` excluded
 * @param defaults      per audience, the document's «Públic → canals per defecte» without `PUSH`: what the seed activates
 * @param push          the audiences that get a web push (fixed by the code, never stored in a template)
 * @param actions       the native action per audience (R-11-11); an audience without an entry gets none
 * @param variables     the template variables (`[[key]]`) of the code, in the document's order
 * @param mandatory     a mandatory template cannot be disabled (`TEMPLATE_MANDATORY`, S11 §3)
 * @param icon          seed icon (S11 §8; `bell` where §8 names none)
 * @param color         seed colour (S11 §8; `NEUTRAL` where §8 names none)
 * @param moduleGuards  the modules without which the code is never emitted (R-11-17, S11 §9)
 * @param stage         `LATER` codes are registered but never seeded nor emitted at R1
 */
public record NotificationSpec(String code, List<String> eventTypes, NotificationCategory category, List<NotificationAudience> audiences,
        Map<NotificationAudience, Set<NotificationChannel>> caps, Map<NotificationAudience, Set<NotificationChannel>> defaults,
        Set<NotificationAudience> push, Map<NotificationAudience, NotificationActionType> actions, List<String> variables,
        Set<String> requiredVariables, boolean mandatory, TemplateIcon icon, TemplateColor color, DedupKeyRule dedupKeyFn,
        RelevanceRule stillRelevantFn, Set<Module> moduleGuards, Stage stage) {
    public enum Stage { R1, LATER }

    public NotificationSpec {
        Objects.requireNonNull(code); Objects.requireNonNull(category); Objects.requireNonNull(icon); Objects.requireNonNull(color);
        Objects.requireNonNull(dedupKeyFn); Objects.requireNonNull(stillRelevantFn); Objects.requireNonNull(stage);
        if (audiences.isEmpty()) { throw new IllegalArgumentException(code + ": no audience"); }
        eventTypes = List.copyOf(eventTypes); audiences = List.copyOf(audiences); variables = List.copyOf(variables);
        requiredVariables = Set.copyOf(requiredVariables); moduleGuards = moduleGuards.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(moduleGuards));
        push = push.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(push));
        caps = frozen(caps); defaults = frozen(defaults);
        actions = actions.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(actions));
        for (var audience : audiences) {
            var allowed = caps.getOrDefault(audience, Set.of());
            var seeded = defaults.getOrDefault(audience, Set.of());
            if (seeded.contains(NotificationChannel.PUSH) || allowed.contains(NotificationChannel.PUSH)) { throw new IllegalArgumentException(code + ": PUSH belongs to push"); }
            if (!allowed.containsAll(seeded)) { throw new IllegalArgumentException(code + ": default channels " + seeded + " outside the caps " + allowed + " of " + audience); }
            if (seeded.isEmpty() && !push.contains(audience)) { throw new IllegalArgumentException(code + ": " + audience + " has no channel"); }
        }
        for (var key : List.of(caps.keySet(), defaults.keySet(), push, actions.keySet())) {
            if (!audiences.containsAll(key)) { throw new IllegalArgumentException(code + ": an audience outside " + audiences); }
        }
        if (!variables.containsAll(requiredVariables)) { throw new IllegalArgumentException(code + ": a required variable is not a variable"); }
    }
    private static Map<NotificationAudience, Set<NotificationChannel>> frozen(Map<NotificationAudience, Set<NotificationChannel>> source) {
        var copy = new EnumMap<NotificationAudience, Set<NotificationChannel>>(NotificationAudience.class);
        source.forEach((audience, channels) -> copy.put(audience, channels.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(channels))));
        return Collections.unmodifiableMap(copy);
    }

    /** A code with a `MessageTemplate` (every category but `SYSTEM`, whose texts are product copy in `messages_*`, R-11-01). */
    public boolean templated() { return category.templated(); }
    public Set<NotificationChannel> caps(NotificationAudience audience) { return caps.getOrDefault(audience, Set.of()); }
    /** The channels the seed activates for the audience, `PUSH` included when the code pushes to it. */
    public Set<NotificationChannel> defaultChannels(NotificationAudience audience) {
        var channels = EnumSet.noneOf(NotificationChannel.class);
        channels.addAll(defaults.getOrDefault(audience, Set.of()));
        if (push.contains(audience)) { channels.add(NotificationChannel.PUSH); }
        return channels;
    }
    /**
     * The seed matrix of the template (S11 §3 `matrix`): `MEMBER · INSTRUCTORS · ADMINS` of the code × `APP · EMAIL · SMS`, a
     * cell `true` where the document activates it. Empty for a `SYSTEM` code (no template).
     */
    public Map<NotificationAudience, Map<NotificationChannel, Boolean>> defaultMatrix() {
        if (!templated()) { return Map.of(); }
        var matrix = new LinkedHashMap<NotificationAudience, Map<NotificationChannel, Boolean>>();
        for (var audience : audiences) {
            if (!audience.templated()) { continue; }
            var row = new LinkedHashMap<NotificationChannel, Boolean>();
            for (var channel : NotificationChannel.values()) {
                if (channel.templated()) { row.put(channel, defaults.getOrDefault(audience, Set.of()).contains(channel)); }
            }
            matrix.put(audience, Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableMap(matrix);
    }
    /** The native action for the audience, or `null` («—» in the document). */
    public NotificationActionType action(NotificationAudience audience) { return actions.get(audience); }
    public String dedupKey(DedupKeyRule.Input input) { return dedupKeyFn.key(code, input); }

    /** R-11-09: how a code builds the `dedupKey` that makes a notification unique in its club. */
    public enum DedupKeyRule {
        /** `{eventId}:{code}:{audience}:{recipientKey}[:{subjectKey}]`. */
        DEFAULT,
        /** N-13: `N-13:{bookingId|trainingBookingId}` — one reminder per booking, whatever the number of `ReminderDue`. */
        PER_BOOKING,
        /** N-24: `{batchId}:{memberId}` — one announcement per member and batch. */
        PER_BATCH_MEMBER;

        /**
         * @param recipientKey the account id, or `email:` + the normalized address ({@link #recipientKey})
         * @param memberId     the recipient's member (N-24)
         * @param subjectKey   the dog when the notification is per dog, otherwise null
         * @param payload      the event payload (`bookingId`/`trainingBookingId` of N-13, `batchId` of N-24)
         */
        public record Input(String eventId, NotificationAudience audience, String recipientKey, String memberId, String subjectKey, Map<String, ?> payload) {
            public Input { payload = payload == null ? Map.of() : payload; }
        }

        public String key(String code, Input input) {
            return switch (this) {
                case DEFAULT -> String.join(":", required(input.eventId(), "eventId"), code, required(input.audience(), "audience").name(),
                        required(input.recipientKey(), "recipientKey")) + (input.subjectKey() == null ? "" : ":" + input.subjectKey());
                case PER_BOOKING -> code + ":" + required(first(input.payload(), "bookingId", "trainingBookingId"), "bookingId|trainingBookingId");
                case PER_BATCH_MEMBER -> required(first(input.payload(), "batchId"), "batchId") + ":" + required(input.memberId(), "memberId");
            };
        }
        /** `recipientKey` of R-11-09: the account id, otherwise `email:` + the trimmed, lower-case address. */
        public static String recipientKey(String accountId, String email) {
            if (accountId != null) { return accountId; }
            return "email:" + required(email, "accountId|email").strip().toLowerCase(Locale.ROOT);
        }
        private static String first(Map<String, ?> payload, String... keys) {
            for (String key : keys) { if (payload.get(key) != null) { return payload.get(key).toString(); } }
            return null;
        }
        private static <T> T required(T value, String name) {
            if (value == null) { throw new IllegalArgumentException("dedupKey needs " + name); }
            return value;
        }
    }

    /** R-11-16: whether an event still deserves its notification when the consumer renders it. */
    public enum RelevanceRule {
        ALWAYS,
        /** N-13: the booking (or training booking) of the reminder is still `ACTIVE` and starts after now; otherwise `SKIPPED_STALE`. */
        BOOKING_ACTIVE_AND_FUTURE;

        /** What E7-T02 reads through its bookings/training port for a reminder. */
        public record Booking(boolean active, Instant startsAt) { }

        /** `booking` is only read by the rules that need it; an unknown booking is no longer relevant. */
        public boolean stillRelevant(Supplier<Optional<Booking>> booking, Instant now) {
            return switch (this) {
                case ALWAYS -> true;
                case BOOKING_ACTIVE_AND_FUTURE -> booking.get().map(b -> b.active() && b.startsAt() != null && b.startsAt().isAfter(now)).orElse(false);
            };
        }
    }
}
