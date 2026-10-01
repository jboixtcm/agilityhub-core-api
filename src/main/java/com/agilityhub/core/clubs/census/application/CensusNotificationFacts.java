package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.Dog;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.clubs.messaging.application.ports.SignupContactPort;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/**
 * The census explains its events to the S11 engine (E7-T02; replaces the notification half of E3's `SignupNotifications`):
 * <ul>
 * <li>N-01 `SignupSubmitted`: the applicant's e-mail (the readmission's `applicant` of the event, else the member's primary
 * address, R-04-06 c) in the submission's language, with the dogs, the plan and — when the submission owes an upfront
 * amount — the total and the club's MANUAL payment instructions (`has_upfront`); the recipient cap of R-04-20 decides the
 * applicant copy once per event ({@link SignupRecipientCap}); the ADMINS get the staff copy with OPEN_SIGNUP.</li>
 * <li>N-02 `MemberValidated`: the APP copy; its e-mail carries the S01 welcome magic link, a credential, so identity keeps
 * sending it (`SignupMails`).</li>
 * <li>N-03 `SignupRejected`: the applicant, in the language of the rejected submission, with the reason.</li>
 * <li>N-01 and N-03 of an existing member (an add-dog request of an `ACTIVE` member): also the APP copy (S04 §8).</li>
 * <li>N-09 `DogLevelChanged`, N-37 `DogRegistered` / `DogDeactivated{CLUB}`, N-29 `BookingBlockChanged`, N-23
 * `DogDocumentPending{MANUAL}` / `DocumentReminderDue`, N-38 `MemberPaymentMethodChanged`: the owner of the dog or the member.</li>
 * </ul>
 */
@Service
public class CensusNotificationFacts implements NotificationFactsPort {
    private static final Set<String> TYPES = Set.of("SignupSubmitted", "MemberValidated", "SignupRejected", "DogLevelChanged", "DogRegistered", "DogDeactivated",
            "BookingBlockChanged", "DogDocumentPending", "DocumentReminderDue", "MemberPaymentMethodChanged");
    private final CensusAccess access; private final CensusClubSettings settings; private final SignupRecipientCap cap;
    private final DocumentService documents;

    public CensusNotificationFacts(CensusAccess access, CensusClubSettings settings, SignupRecipientCap cap,
            DocumentService documents) {
        this.access = access; this.settings = settings; this.cap = cap; this.documents = documents;
    }

    @Override public Set<String> eventTypes() { return TYPES; }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        return switch (trigger.type()) {
            case "SignupSubmitted", "SignupRejected", "MemberValidated" -> signup(trigger);
            case "DogLevelChanged" -> dog(trigger, trigger.text("dogId")).map(builder -> builder.value("level_name", level(trigger.text("after"))).build());
            // N-37: `change` (ICU select) tells the new dog from the one the club deactivated.
            case "DogRegistered" -> dog(trigger, trigger.text("dogId")).map(builder -> builder.value("change", trigger.type()).build());
            case "DogDeactivated" -> "CLUB".equals(trigger.text("reason"))
                    ? dog(trigger, trigger.text("dogId")).map(builder -> builder.value("change", trigger.type()).build()) : Optional.empty();
            case "DogDocumentPending", "DocumentReminderDue" -> {
                if ("DogDocumentPending".equals(trigger.type()) && !"MANUAL".equals(trigger.text("trigger"))) { yield Optional.empty(); }
                String type = trigger.text("type");
                yield dog(trigger, trigger.text("dogId")).map(builder -> builder.value("document_type", new NotificationValues.Localized(locale -> documentLabel(type, locale))).build());
            }
            case "BookingBlockChanged" -> member(trigger).map(builder -> builder.value("reason", trigger.text("reason")).value("active", trigger.flag("active")).build());
            case "MemberPaymentMethodChanged" -> member(trigger).map(builder -> builder.value("masked_account", trigger.text("masked")).build());
            default -> Optional.empty();
        };
    }

    /** The dog's owner as the MEMBER subject (one notification per dog). */
    private Optional<NotificationFacts.Builder> dog(NotificationTrigger trigger, String dogId) {
        if (dogId == null) { return Optional.empty(); }
        var dog = access.dogs.findById(dogId).orElse(null);
        if (dog == null) { return Optional.empty(); }
        String memberId = Objects.requireNonNullElse(trigger.text("memberId"), dog.memberId);
        return Optional.of(NotificationFacts.builder().value("entityId", dog.id).member(new NotificationFacts.MemberSubject(memberId, dog.id, Map.of("dog_name", dog.name), NotificationSubject.dog(dog.id))));
    }
    private Optional<NotificationFacts.Builder> member(NotificationTrigger trigger) {
        String memberId = trigger.text("memberId");
        return memberId == null ? Optional.empty() : Optional.of(NotificationFacts.builder().member(memberId, null).subject(NotificationSubject.member(memberId)));
    }

    /** N-01 / N-02 / N-03 (S04 §8), with the E3 rules of `SignupNotifications` unchanged. */
    private Optional<NotificationFacts> signup(NotificationTrigger trigger) {
        String id = trigger.text("memberId");
        var member = id == null ? null : access.members.findById(id).orElse(null);
        if (member == null || member.erasedAt != null) { return Optional.empty(); }
        var person = map(trigger.payload().get("applicant")); boolean submitted = !person.isEmpty();
        // E7-T06: the full name, as every other audience's `member_name` (first name and both surnames), so the engine derives the
        // applicant's `member_last_names` from it; each part stripped, the first name too, so the full name starts with it. A
        // readmission's `applicant` carries both surnames since E81 (an event written before carries `lastName1` only).
        String firstName = stripped(submitted ? string(person.get("firstName")) : member.firstName);
        String memberName = Stream.of(firstName, submitted ? string(person.get("lastName1")) : member.lastName1, submitted ? string(person.get("lastName2")) : member.lastName2)
                .map(CensusNotificationFacts::stripped).filter(part -> part != null && !part.isEmpty()).collect(Collectors.joining(" "));
        String email = submitted ? string(person.get("email")) : rows(member.contactEmails).isEmpty() ? null : string(rows(member.contactEmails).getFirst().get("email"));
        String locale = string((submitted ? person : map(member.signup)).getOrDefault("locale", access.config().club().defaultLocale()));
        var builder = NotificationFacts.builder().subject(NotificationSubject.member(id)).value("ADMINS", "entityId", id)
                .value("member_name", memberName).value("member_first_name", firstName).value("gender", submitted ? person.get("gender") : member.gender);
        switch (trigger.type()) {
            case "MemberValidated" -> {
                // The welcome e-mail carries the S01 magic link: identity sends it on the SYSTEM path (SignupMails), never stored here.
                return Optional.of(builder.member(id, null).exclude("EMAIL").build());
            }
            case "SignupRejected" -> {
                if (trigger.payload().get("locale") != null) { locale = string(trigger.payload().get("locale")); }
                else if (!submitted) { locale = string(submission(member, dogs(trigger)).getOrDefault("locale", locale)); }
                builder.value("reason", trigger.text("reason"));
                return email == null ? Optional.empty() : Optional.of(builder.applicant(new SignupContactPort.ApplicantContact(email, locale, memberName, existing(trigger, member))).build());
            }
            default -> { }
        }
        // SignupSubmitted (E3-T10/E3-T12): the locale, the dog names, the plan and the upfront total travel in the event; an
        // event written before the payload carried them (no `locale`) reads the submission block, as before.
        var payload = trigger.payload(); boolean carried = payload.containsKey("locale");
        var dogs = carried ? List.<Dog>of() : dogs(trigger);
        var signup = carried ? Map.<String, Object>of() : submission(member, dogs);
        locale = string(carried ? payload.get("locale") : signup.getOrDefault("locale", locale));
        builder.value("dogs", String.join(", ", carried ? names(payload.get("dogNames")) : dogs.stream().map(d -> d.name).toList()));
        String planId = payload.containsKey("planId") ? string(payload.get("planId")) : string(signup.get("planIdRequested"));
        var names = map(access.references.plan(planId).get("name")); if (names.get("values") instanceof Map<?, ?>) { names = map(names.get("values")); }
        builder.value("plan_name", string(names.getOrDefault(locale, names.getOrDefault(access.config().club().defaultLocale(), ""))));
        var total = map(carried ? payload.get("upfrontTotal") : map(signup.get("upfront")).get("totalDue"));
        long due = total.get("amountMinor") instanceof Number number ? number.longValue() : 0;
        builder.value("APPLICANT", "has_upfront", due > 0 ? "true" : "false");
        if (due > 0) {
            // With a provider checkout the payment happens there (pay_link, E8-T04); otherwise the club's MANUAL instructions apply.
            String instructions = Boolean.TRUE.equals(payload.get("checkoutRequired")) ? null : settings.manualInstructions(locale);
            builder.value("APPLICANT", "upfront_total", new Money(due, string(total.get("currency"))))
                    .value("APPLICANT", "payment_instructions", instructions == null ? "" : instructions)
                    .value("APPLICANT", "pay_link", "");
        }
        // R-04-20: the applicant's copy is capped per address; the admins always get theirs.
        if (email != null && cap.admitted(trigger.eventId(), trigger.clubId(), trigger.type(), email)) {
            builder.applicant(new SignupContactPort.ApplicantContact(email, locale, memberName, existing(trigger, member)));
        } else { builder.audiences("ADMINS"); }
        cap.retainAfterCommit(trigger.eventId(), trigger.clubId(), trigger.type());
        return Optional.of(builder.build());
    }

    /**
     * S04 §8 «abonat existent»: the account that also gets the APP copy when the event says the request came from a validated
     * member (`source = APP_ADD_DOG`, `memberWasActive`), never the member's state when the notice is built; else null.
     */
    private static String existing(NotificationTrigger trigger, Member member) {
        boolean existing = "SignupRejected".equals(trigger.type()) ? trigger.flag("memberWasActive") : "APP_ADD_DOG".equals(trigger.text("source"));
        return existing ? member.accountId : null;
    }
    private List<Dog> dogs(NotificationTrigger trigger) {
        var ids = trigger.ids("dogIds");
        return ids.isEmpty() ? List.of() : access.dogs.matching(Criteria.where("_id").in(ids));
    }
    private static List<String> names(Object raw) { return raw instanceof Collection<?> values ? values.stream().map(String::valueOf).toList() : List.of(); }
    private static String stripped(String text) { return text == null ? null : text.strip(); }
    /** The block of the submission these dogs came from (S04 §3): the oldest one, the member's signup for a dog written before the blocks. */
    private static Map<String, Object> submission(Member member, List<Dog> dogs) {
        return dogs.stream().filter(d -> d.signup != null).map(d -> map(d.signup))
                .min(Comparator.comparing((Map<String, Object> b) -> Objects.requireNonNullElse(instant(b.get("submittedAt")), Instant.MAX))).orElse(map(member.signup));
    }
    /** The level's `LocalizedText` name (S05), or null for an unknown level. */
    private LocalizedText level(String levelId) {
        if (levelId == null) { return null; }
        var names = map(access.references.level(levelId).get("name"));
        if (names.get("values") instanceof Map<?, ?>) { names = map(names.get("values")); }
        var values = new LinkedHashMap<String, String>();
        names.forEach((key, value) -> { if (value != null) { values.put(key, value.toString()); } });
        return values.isEmpty() ? null : new LocalizedText(values, access.config().club().defaultLocale());
    }
    private String documentLabel(String type, Locale locale) {
        try (var scope = LocaleContext.open(locale)) {
            return documents.types().stream().filter(row -> Objects.equals(row.get("key"), type)).map(row -> string(row.get("label"))).findFirst().orElse(Objects.toString(type, ""));
        }
    }
}
