package com.agilityhub.core.clubs.messaging.application.ports;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the owning context of an event tells the engine about one catalog code of it (S11 §6 `spec.recipients` and
 * `spec.variables`): who the notice is about, the raw values of its variables, and the few exceptions the catalog leaves
 * to the owner. Anything left `null` takes the engine's default (R-11-02 over the payload). Audiences, channels and
 * categories are the catalog's names (`MEMBER`, `EMAIL`, `CLUB_CHANGES` …): the port speaks the catalog, not the engine's
 * types, so owners only depend on `messaging.application` (ArchUnit `E0_T01_crossContextAccessUsesApplication`).
 *
 * @param occurrence       the first part of the default `dedupKey` instead of the event id: one notice for several events
 *                         (N-33 per week, N-42 per process and day); `null` = the event id
 * @param audiences        the code's audiences this event reaches (N-08a without staff on `RISK_REVIEW`); `null` = all of them
 * @param members          `MEMBER` recipients, one notification per subject (per dog); `null` = the payload's `memberId`/`dogId`
 *                         and `affected[]`
 * @param instructors      `INSTRUCTORS`: the class's, explicit ids, or `null` = the payload's `classId`, else every active one
 * @param applicant        `APPLICANT`; `null` = the {@link SignupContactPort} applicant of the payload's `memberId`
 * @param values           raw variables shared by every recipient ({@link NotificationValues})
 * @param subject          the subject ids shared by every recipient
 * @param excludedChannels channels the owner delivers itself (N-02's e-mail carries the S01 welcome link, a credential)
 * @param audienceValues   raw variables of one audience only (N-01: the applicant's copy tells the upfront amount, the admins' not)
 * @param categoryOverride the catalog's Annex A variant of a code (N-32b made from the back office → `CLUB_CHANGES`, S07):
 *                         the notification takes that category, and its channels are that category's caps for the audience
 * @param enabledChannels  channels the event itself switches on, within the code's caps (N-32a: `ActivityPublished.notifyEmail`
 *                         — «+EMAIL si el club ho marca»); the member's preferences still apply
 */
public record NotificationFacts(String occurrence, Set<String> audiences, List<MemberSubject> members, InstructorScope instructors,
        SignupContactPort.ApplicantContact applicant, Map<String, Object> values, NotificationSubject subject, Set<String> excludedChannels,
        Map<String, Map<String, Object>> audienceValues, String categoryOverride, Set<String> enabledChannels) {
    public static final Set<String> AUDIENCES = Set.of("MEMBER", "INSTRUCTORS", "ADMINS", "APPLICANT");
    public static final Set<String> CHANNELS = Set.of("APP", "EMAIL", "SMS", "PUSH");

    public NotificationFacts {
        audiences = audiences == null ? null : Set.copyOf(audiences);
        members = members == null ? null : List.copyOf(members);
        values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        subject = subject == null ? NotificationSubject.NONE : subject;
        excludedChannels = excludedChannels == null ? Set.of() : Set.copyOf(excludedChannels);
        audienceValues = audienceValues == null ? Map.of() : Map.copyOf(audienceValues);
        enabledChannels = enabledChannels == null ? Set.of() : Set.copyOf(enabledChannels);
    }
    public Map<String, Object> valuesOf(String audience) { return audienceValues.getOrDefault(audience, Map.of()); }

    /**
     * One `MEMBER` notification: the member, the dog it is about (the `subjectKey`), its own values and subject ids, and its
     * own `occurrence` when it differs from the facts' one (N-46: each lost offer, `{entryId}:{notifiedAt}`).
     */
    public record MemberSubject(String memberId, String dogId, Map<String, Object> values, NotificationSubject subject, String occurrence) {
        public MemberSubject {
            java.util.Objects.requireNonNull(memberId);
            values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
            subject = subject == null ? NotificationSubject.NONE : subject;
        }
        public MemberSubject(String memberId, String dogId, Map<String, Object> values, NotificationSubject subject) { this(memberId, dogId, values, subject, null); }
        public MemberSubject(String memberId, String dogId) { this(memberId, dogId, Map.of(), NotificationSubject.NONE, null); }
    }
    /** `INSTRUCTORS` of a class (`classSessionId`), plus explicit catalog ids (N-08b's former instructors); both may be given. */
    public record InstructorScope(String classSessionId, Collection<String> instructorIds) {
        public InstructorScope { instructorIds = instructorIds == null ? List.of() : List.copyOf(instructorIds); }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String occurrence; private Set<String> audiences; private List<MemberSubject> members; private InstructorScope instructors;
        private SignupContactPort.ApplicantContact applicant; private final Map<String, Object> values = new LinkedHashMap<>();
        private NotificationSubject subject = NotificationSubject.NONE; private final Set<String> excluded = new LinkedHashSet<>();
        private final Map<String, Map<String, Object>> byAudience = new LinkedHashMap<>();
        private String category; private final Set<String> enabled = new LinkedHashSet<>();

        public Builder occurrence(String key) { occurrence = key; return this; }
        public Builder audiences(String... only) { audiences = new LinkedHashSet<>(); for (String audience : only) { audiences.add(audience(audience)); } return this; }
        public Builder member(String memberId, String dogId) { return member(new MemberSubject(memberId, dogId)); }
        public Builder member(MemberSubject member) { if (members == null) { members = new ArrayList<>(); } members.add(member); return this; }
        /** No `MEMBER` recipient at all (an empty, explicit list: the payload default does not apply). */
        public Builder noMembers() { if (members == null) { members = new ArrayList<>(); } return this; }
        public Builder instructors(String classSessionId, Collection<String> instructorIds) { instructors = new InstructorScope(classSessionId, instructorIds); return this; }
        public Builder applicant(SignupContactPort.ApplicantContact contact) { applicant = contact; return this; }
        public Builder value(String key, Object value) { if (value != null) { values.put(key, value); } return this; }
        public Builder values(Map<String, ?> more) { more.forEach(this::value); return this; }
        public Builder value(String audience, String key, Object value) {
            if (value != null) { byAudience.computeIfAbsent(audience(audience), ignored -> new LinkedHashMap<>()).put(key, value); }
            return this;
        }
        public Builder subject(NotificationSubject ids) { subject = subject.with(ids); return this; }
        public Builder exclude(String channel) { excluded.add(channel(channel)); return this; }
        public Builder enable(String channel) { enabled.add(channel(channel)); return this; }
        public Builder category(String variant) { category = variant; return this; }
        public NotificationFacts build() {
            var perAudience = new LinkedHashMap<String, Map<String, Object>>();
            byAudience.forEach((audience, map) -> perAudience.put(audience, Map.copyOf(map)));
            return new NotificationFacts(occurrence, audiences, members, instructors, applicant, values, subject, excluded, perAudience, category, enabled);
        }
        private static String audience(String name) {
            if (!AUDIENCES.contains(name)) { throw new IllegalArgumentException("Not a catalog audience: " + name); }
            return name;
        }
        private static String channel(String name) {
            if (!CHANNELS.contains(name)) { throw new IllegalArgumentException("Not a catalog channel: " + name); }
            return name;
        }
    }
}
