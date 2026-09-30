package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.InMemoryMessagingPorts;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.support.ConcurrencySupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T02 steps 2–5 and 12 end to end over real documents (T-11-24, T-11-08, T-11-13, T-11-32, T-11-31, T-11-01, T-11-40,
 * T-11-21, T-11-11, T-11-25): the outbox event becomes the catalog code's notifications — template (seeded on first use),
 * relevance, recipients, locale, formatted variables, rendered texts, `dedupKey` and the R-11-03 deliveries — and the
 * dispatcher sends them right after the commit.
 */
class NotificationEngineIT extends EngineFixtures {
    private static final String RAIN = "La classe queda anul·lada per la pluja. Podeu reservar-ne una altra des de l'app. Disculpeu les molèsties!";

    private Map<String, Object> cancellation(String reason) {
        return Map.of("classId", "e7t02-class-a", "reason", reason, "adminText", RAIN,
                "affected", affected("member-laura", "dog-duna", "booking-1", "member-laura", "dog-rock", "booking-2", "member-marc", "dog-ares", "booking-3"),
                "waitlistIds", List.of("e7t02-entry-pau"));
    }

    @Test void T_11_24_classCancelledByClubReachesEveryDogTheInstructorAndTheAdminsOnTheirChannels() {
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        waitlistEntry(CLUB, "e7t02-entry-pau", "e7t02-class-a", "member-pau", "dog-nit");
        String eventId = deliver(CLUB, "ClassCancelledByClub", cancellation("WEATHER"));
        var all = stored(CLUB, "N-08a");
        // 4 MEMBER (3 booked dogs + the waiting one), 1 INSTRUCTORS, 2 ADMINS.
        assertThat(all).extracting(Notification::audience).containsExactlyInAnyOrder(MEMBER, MEMBER, MEMBER, MEMBER, INSTRUCTORS, ADMINS, ADMINS);
        var members = all.stream().filter(n -> n.audience() == MEMBER).toList();
        assertThat(members).extracting(n -> n.recipient().memberId() + "/" + n.subject().dogId()).containsExactlyInAnyOrder("member-laura/dog-duna",
                "member-laura/dog-rock", "member-marc/dog-ares", "member-pau/dog-nit");
        // APP DELIVERED + EMAIL SENT + one SMS per phone SENT, right after the commit.
        var duna = members.stream().filter(n -> "dog-duna".equals(n.subject().dogId())).findFirst().orElseThrow();
        assertThat(channels(duna)).containsExactly("APP:DELIVERED", "EMAIL:SENT", "SMS:SENT", "SMS:SENT");
        assertThat(duna.deliveries()).extracting(Notification.Delivery::target).containsExactly("account-laura", "laura@example.test", "+34600000001", "+34600000002");
        assertThat(duna.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.SMS).allSatisfy(d -> { assertThat(d.providerRef()).startsWith("fake-sms-"); assertThat(d.attempts()).isEqualTo(1); });
        assertThat(channels(members.stream().filter(n -> "member-pau".equals(n.recipient().memberId())).findFirst().orElseThrow()))
                .containsExactly("APP:DELIVERED", "EMAIL:SENT", "SMS:SENT");
        assertThat(duna.dedupKey()).isEqualTo(eventId + ":N-08a:MEMBER:account-laura:dog-duna");
        assertThat(duna.eventId()).isEqualTo(eventId); assertThat(duna.eventType()).isEqualTo("ClassCancelledByClub"); assertThat(duna.templateId()).isNotNull();
        assertThat(duna.action().type()).isEqualTo(NotificationActionType.CHANGE_CLASS); assertThat(duna.action().params()).containsEntry("dogId", "dog-duna");
        // Rendered in each member's language with the club's formats: Laura ca, Marc es.
        assertThat(duna.locale()).isEqualTo("ca"); assertThat(duna.title()).isEqualTo("Classe anul·lada pel club");
        assertThat(duna.body()).isEqualTo("Demà · 18:50 · B+C, amb Duna. «" + RAIN + "» — Club Agility Exemple. Aquesta sessió no compta al teu còmput.");
        assertThat(duna.smsBody()).hasSizeLessThanOrEqualTo(160).startsWith("Club Agility Exemple: la classe de demà a les 18:50 (B+C) queda anul.lada.") // S11 §8 (E7-T03)
                .doesNotContain("http", "·");
        var marc = members.stream().filter(n -> "member-marc".equals(n.recipient().memberId())).findFirst().orElseThrow();
        assertThat(marc.locale()).isEqualTo("es"); assertThat(marc.body()).startsWith("Mañana · 18:50 · B+C, con Ares.");
        assertThat(sms.lastTo("+34600000003").body()).isEqualTo(marc.smsBody());
        // The instructor: APP + EMAIL with the non-editable staff copy; the admins: APP only (the seed), never an SMS.
        var marta = all.stream().filter(n -> n.audience() == INSTRUCTORS).findFirst().orElseThrow();
        assertThat(channels(marta)).containsExactly("APP:DELIVERED", "EMAIL:SENT");
        assertThat(marta.body()).isEqualTo("Club Agility Exemple: demà · 18:50 · B+C — classe anul·lada. " + RAIN);
        assertThat(all.stream().filter(n -> n.audience() == ADMINS)).allSatisfy(n -> assertThat(channels(n)).containsExactly("APP:DELIVERED"))
                .extracting(n -> n.recipient().accountId()).containsExactlyInAnyOrder("account-admin", "account-admin2");
        // The e-mails: subject = title, the club's layout and sender, the deep link of CHANGE_CLASS in the club app (04 with each
        // dog preselected, R-11-11; round 2), never the unsubscribe footer (CLUB_CHANGES).
        var mailToLaura = mail.to("laura@example.test");
        assertThat(mailToLaura).hasSize(2).allSatisfy(m -> {
            assertThat(m.subject()).isEqualTo("Classe anul·lada pel club"); assertThat(m.html()).contains("Club Agility Exemple").doesNotContain("/notificacions");
            assertThat(m.headers()).isEmpty(); assertThat(m.tags()).containsEntry("clubId", CLUB);
        }).extracting(m -> m.text().lines().filter(line -> line.startsWith("Obre l’app: ")).findFirst().orElse(""))
                .containsExactlyInAnyOrder("Obre l’app: https://engine.example.test/reservar?dogId=dog-duna", "Obre l’app: https://engine.example.test/reservar?dogId=dog-rock");
        assertThat(mail.to("marta@example.test")).singleElement().satisfies(m -> assertThat(m.text()).contains("classe anul·lada. " + RAIN,
                "Obre l’app: https://engine.example.test/instructor/classes/e7t02-class-a"));
        // One NotificationQueued per notification and channel, one NotificationSent per accepted delivery.
        assertThat(outbox(CLUB, "NotificationQueued")).hasSize(4 * 3 + 2 + 2);
        assertThat(outbox(CLUB, "NotificationSent")).hasSize(5 + 6); // 5 e-mails, 6 SMS (APP is born DELIVERED)

        // reason = RISK_REVIEW: the members only, with the automatic text in each one's language.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        deliver(CLUB, "ClassCancelledByClub", cancellation("RISK_REVIEW"));
        assertThat(stored(CLUB, "N-08a")).hasSize(4).allSatisfy(n -> {
            assertThat(n.audience()).isEqualTo(MEMBER); assertThat(n.body()).doesNotContain(RAIN).contains(n.locale().equals("es") ? "mínimo" : "mínim");
        });
        // ClassAutoCancelled → N-17 to the class's instructors and the admins (APP + EMAIL), even without registrants.
        deliver(CLUB, "ClassAutoCancelled", Map.of("classId", "e7t02-class-a", "dogsCount", 1));
        assertThat(stored(CLUB, "N-17")).extracting(Notification::audience).containsExactlyInAnyOrder(INSTRUCTORS, ADMINS, ADMINS);
        assertThat(stored(CLUB, "N-17")).allSatisfy(n -> assertThat(channels(n)).containsExactly("APP:DELIVERED", "EMAIL:SENT"));
        // A class without registrants notifies nobody on a cancellation.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "affected", List.of()));
        assertThat(stored(CLUB, "N-08a")).isEmpty();
        // A class that no longer exists (or another club's event without a club) is nothing.
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "class-gone", "reason", "DELETED", "affected", affected("member-marc", "dog-ares", "b")));
        assertThat(stored(CLUB, "N-08a")).isEmpty();
    }

    @Test void T_11_08_reprocessingTheSameEventCreatesNoDocumentNorDeliveryAndTwoDogsOrTwoAudiencesAreTwoNotifications() {
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        waitlistEntry(CLUB, "e7t02-entry-pau", "e7t02-class-a", "member-pau", "dog-nit");
        String eventId = deliver(CLUB, "ClassCancelledByClub", cancellation("WEATHER"));
        var first = stored(CLUB, "N-08a"); int smsCount = sms.messages().size(), mailCount = mail.sent.size();
        long queued = outbox(CLUB, "NotificationQueued").size();
        deliver(eventId, CLUB, "ClassCancelledByClub", cancellation("WEATHER"));
        var again = stored(CLUB, "N-08a");
        assertThat(again).hasSameSizeAs(first);
        assertThat(again).extracting(Notification::id).containsExactlyElementsOf(first.stream().map(Notification::id).toList());
        assertThat(again).extracting(n -> n.deliveries().size()).containsExactlyElementsOf(first.stream().map(n -> n.deliveries().size()).toList());
        assertThat(sms.messages()).hasSize(smsCount); assertThat(mail.sent).hasSize(mailCount);
        assertThat(outbox(CLUB, "NotificationQueued")).hasSize((int) queued);
        // Laura's two dogs are two notifications; an admin who also booked is one MEMBER and one ADMINS notification.
        assertThat(first).filteredOn(n -> "member-laura".equals(n.recipient().memberId())).hasSize(2);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-admin", "dog-admin", "booking-9")));
        assertThat(stored(CLUB, "N-08a")).filteredOn(n -> "account-admin".equals(n.recipient().accountId())).extracting(Notification::audience)
                .containsExactlyInAnyOrder(MEMBER, ADMINS);
        // S11 §6 `addDeliveryIfAbsent`: a redelivery after the member switched e-mail back on adds that delivery once, and nothing else.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, null, Map.of("emailByCategory", Map.of("CLUB_CHANGES", false)), null, null));
        var cancelMarc = Map.<String, Object>of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b3"));
        String marcEvent = deliver(CLUB, "ClassCancelledByClub", cancelMarc);
        java.util.function.Supplier<Notification> marcNotice = () -> stored(CLUB, "N-08a").stream().filter(n -> n.audience() == MEMBER).findFirst().orElseThrow();
        assertThat(channels(marcNotice.get())).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_BY_PREFERENCE", "SMS:SENT");
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, null, Map.of("emailByCategory", Map.of("CLUB_CHANGES", true)), null, null));
        deliver(marcEvent, CLUB, "ClassCancelledByClub", cancelMarc); deliver(marcEvent, CLUB, "ClassCancelledByClub", cancelMarc);
        assertThat(channels(marcNotice.get())).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_BY_PREFERENCE", "SMS:SENT", "EMAIL:SENT");
        assertThat(mail.to("marc@example.test")).hasSize(2); // the first event's e-mail and this one
    }

    @Test void T_11_13_T_11_32_aReminderOfAnActiveBookingOnceAndOfACancelledOneNever() {
        subscribe(CLUB, "subscription-laura", "account-laura");
        var startsAt = Instant.parse("2026-10-07T16:50:00Z"); // 18:50 today in Madrid
        ports.activeBookings.put("booking-1", startsAt);
        var reminder = Map.<String, Object>of("bookingId", "booking-1", "memberId", "member-laura", "dogId", "dog-duna", "startsAt", startsAt.toString());
        deliver(CLUB, "ReminderDue", reminder);
        var n13 = only(CLUB, "N-13");
        assertThat(n13.dedupKey()).isEqualTo("N-13:booking-1"); assertThat(n13.title()).isEqualTo("Recordatori de classe");
        assertThat(n13.body()).isEqualTo("Avui a les 18:50 · B+C · Central · amb Duna."); // S11 §8's N-13 text (E7-T03 seed)
        // APP + PUSH; EMAIL only with emailByCategory.OPERATIONAL (off by default).
        assertThat(channels(n13)).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_BY_PREFERENCE", "PUSH:SENT");
        assertThat(push.sent()).singleElement().satisfies(p -> { assertThat(p.subscriptionId()).isEqualTo("subscription-laura"); assertThat(p.payload().tag()).isEqualTo("N-13");
            assertThat(p.payload().url()).isEqualTo("/notificacions?id=" + n13.id()); });
        // T-11-32: a second ReminderDue of the same booking is the same reminder.
        deliver(CLUB, "ReminderDue", reminder);
        assertThat(stored(CLUB, "N-13")).hasSize(1); assertThat(push.sent()).hasSize(1);
        // The booking is cancelled: a later reminder leaves the stored one untouched.
        ports.activeBookings.remove("booking-1");
        deliver(CLUB, "ReminderDue", reminder);
        assertThat(channels(only(CLUB, "N-13"))).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_BY_PREFERENCE", "PUSH:SENT");
        // T-11-13: a reminder whose booking was already cancelled → no delivery, one SKIPPED_STALE record; not in the feed.
        var stale = Map.<String, Object>of("bookingId", "booking-2", "memberId", "member-laura", "dogId", "dog-rock", "startsAt", startsAt.toString());
        deliver(CLUB, "ReminderDue", stale);
        var skipped = stored(CLUB, "N-13").stream().filter(n -> "N-13:booking-2".equals(n.dedupKey())).findFirst().orElseThrow();
        assertThat(channels(skipped)).containsExactly("APP:SKIPPED_STALE"); assertThat(skipped.title()).isEmpty();
        assertThat(push.sent()).hasSize(1); assertThat(mail.sent).isEmpty();
        try (var tenant = TenantContext.open(CLUB)) { assertThat(notifications.unreadApp("account-laura")).isEqualTo(1); }
        // A booking that already started is no longer relevant either.
        ports.activeBookings.put("booking-3", clock.instant().minusSeconds(60));
        deliver(CLUB, "ReminderDue", Map.of("bookingId", "booking-3", "memberId", "member-laura", "dogId", "dog-duna", "startsAt", startsAt.toString()));
        assertThat(channels(stored(CLUB, "N-13").stream().filter(n -> "N-13:booking-3".equals(n.dedupKey())).findFirst().orElseThrow())).containsExactly("APP:SKIPPED_STALE");
        // kind = TRAINING: the training title and the «8:00–8:30» slot (R-11-16, R-11-05) in Marc's Spanish.
        ports.activeBookings.put("training-1", Instant.parse("2026-10-08T06:00:00Z"));
        deliver(CLUB, "ReminderDue", Map.of("trainingBookingId", "training-1", "memberId", "member-marc", "dogId", "dog-ares", "startsAt", "2026-10-08T06:00:00Z"));
        var training = stored(CLUB, "N-13").stream().filter(n -> "N-13:training-1".equals(n.dedupKey())).findFirst().orElseThrow();
        assertThat(training.title()).isEqualTo("Recordatorio de entrenamiento"); assertThat(training.body()).isEqualTo("Mañana a las 8:00–8:30 · Entrenamiento libre · Central · con Ares.");
        assertThat(training.action().params()).containsEntry("trainingBookingId", "training-1").containsEntry("dogId", "dog-ares");
        assertThat(channels(training)).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_BY_PREFERENCE", "PUSH:SKIPPED_NO_CONTACT");
    }

    @Test void T_11_31_aHundredSimultaneousRemindersAreAHundredNotificationsWithoutDuplicates() throws Exception {
        var startsAt = Instant.parse("2026-10-08T16:50:00Z");
        for (int i = 0; i < 100; i++) { ports.activeBookings.put("booking-" + i, startsAt); }
        // 200 deliveries at once: every booking's reminder twice (two ReminderDue of the same booking, T-11-32). A delivery that
        // loses the race on `{clubId, dedupKey}` rolls back and is delivered again, as the outbox redelivers it (R-11-09).
        var redeliveries = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>();
        ConcurrencySupport.parallel(200, i -> () -> {
            boolean laura = i % 2 == 0;
            var payload = Map.<String, Object>of("bookingId", "booking-" + (i % 100), "memberId", laura ? "member-laura" : "member-marc", "dogId", laura ? "dog-duna" : "dog-ares",
                    "startsAt", startsAt.toString());
            String eventId = "reminder-" + i;
            for (int attempt = 1; ; attempt++) {
                try { deliver(eventId, CLUB, "ReminderDue", payload); return null; }
                catch (RuntimeException raced) {
                    if (attempt == 10) { throw raced; }
                    var cause = raced; while (cause.getCause() instanceof RuntimeException inner) { cause = inner; }
                    redeliveries.computeIfAbsent(cause.getClass().getSimpleName(), key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                }
            }
        });
        System.out.println("T-11-31 200 concurrent ReminderDue deliveries (100 bookings x 2); redelivered after a rolled-back attempt: " + redeliveries);
        var reminders = stored(CLUB, "N-13");
        assertThat(reminders).hasSize(100);
        assertThat(reminders.stream().map(Notification::dedupKey).distinct()).hasSize(100);
        assertThat(reminders).allSatisfy(n -> assertThat(n.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.APP).hasSize(1));
        // The first-use template was created once for the club.
        try (var tenant = TenantContext.open(CLUB)) { assertThat(templateRepository.findByCode("N-13")).isPresent(); }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-13")), MessageTemplate.class)).isEqualTo(1);
    }

    @Test void T_11_01_T_11_40_theRecipientsLanguageWithFallbacksAndANewLanguageOnlyForLaterNotices() {
        // A club template whose body is only `ca`: an `en` recipient reads the ca text with the date in English (R-11-01 example).
        var body = new LocalizedText(Map.of("ca", "Hola [[member_first_name]], la baixa és efectiva el [[effective_date]]."), "ca");
        try (var tenant = TenantContext.open(CLUB)) {
            var seed = templates.forCode(NotificationCatalog.byCode("N-28").orElseThrow(), "ca");
            mongo.save(new MessageTemplate(seed.id(), CLUB, "N-28", TemplateKind.CATALOG, seed.category(), new LocalizedText(Map.of("ca", "Baixa", "es", "Baja"), "ca"),
                    body, null, seed.icon(), seed.color(), seed.matrix(), true, false, true, TemplateStatus.ACTIVE, seed.version(), seed.createdAt(), "admin", clock.instant(), "admin"));
        }
        deliver(CLUB, "LeaveResolved", Map.of("memberId", "member-anna", "effectiveDate", "2026-10-31"));
        var anna = only(CLUB, "N-28");
        assertThat(anna.locale()).isEqualTo("en"); assertThat(anna.title()).isEqualTo("Baixa");
        assertThat(anna.body()).isEqualTo("Hola Anna, la baixa és efectiva el October 31, 2026.");
        // `es` with an `es` title reads it; an account without a language takes the member's own (the signup's), then the club's default.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-marc")), new Update().unset("locale"), "accounts");
        deliver(CLUB, "LeaveResolved", Map.of("memberId", "member-marc", "effectiveDate", "2026-10-31"));
        assertThat(only(CLUB, "N-28").locale()).isEqualTo("es"); assertThat(only(CLUB, "N-28").title()).isEqualTo("Baja");
        assertThat(only(CLUB, "N-28").body()).isEqualTo("Hola Marc, la baixa és efectiva el 31 de octubre de 2026.");
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        ports.update("member-marc", c -> new MemberContact(c.memberId(), c.accountId(), c.displayName(), c.firstName(), c.gender(), "de", c.emails(), c.phones(),
                null, "ACTIVE", c.dogs()));
        deliver(CLUB, "LeaveResolved", Map.of("memberId", "member-marc", "effectiveDate", "2026-10-31"));
        assertThat(only(CLUB, "N-28").locale()).isEqualTo("ca");
        // APPLICANT: the signup's language (N-03 to an applicant who wrote in English).
        ports.applicants.put("member-new", new com.agilityhub.core.clubs.messaging.application.ports.SignupContactPort.ApplicantContact("new@example.test", "en", "New Person"));
        deliver(CLUB, "SignupRejected", Map.of("memberId", "member-new", "reason", "Duplicate"));
        var applicant = only(CLUB, "N-03");
        assertThat(applicant.audience()).isEqualTo(APPLICANT); assertThat(applicant.locale()).isEqualTo("en");
        assertThat(applicant.recipient().email()).isEqualTo("new@example.test"); assertThat(applicant.dedupKey()).endsWith(":N-03:APPLICANT:email:new@example.test");
        assertThat(channels(applicant)).containsExactly("EMAIL:SENT"); assertThat(mail.to("new@example.test")).singleElement().satisfies(m -> assertThat(m.locale().getLanguage()).isEqualTo("en"));

        // T-11-40: Laura (ca) gets a notice; she switches to es; the next one is es and the first keeps its ca text.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        deliver(CLUB, "DogRegistered", Map.of("memberId", "member-laura", "dogId", "dog-duna"));
        var before = only(CLUB, "N-37");
        assertThat(before.locale()).isEqualTo("ca");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-laura")), new Update().set("locale", "es"), "accounts");
        deliver(CLUB, "DogRegistered", Map.of("memberId", "member-laura", "dogId", "dog-rock"));
        var after = stored(CLUB, "N-37").stream().filter(n -> !n.id().equals(before.id())).findFirst().orElseThrow();
        assertThat(after.locale()).isEqualTo("es");
        assertThat(mongo.findById(before.id(), Notification.class)).satisfies(n -> { assertThat(n.locale()).isEqualTo("ca"); assertThat(n.title()).isEqualTo(before.title());
            assertThat(n.body()).isEqualTo(before.body()); });
        assertThat(before.body()).isEqualTo("Duna ja forma part del club."); assertThat(after.body()).isEqualTo("Rock ya forma parte del club.");
    }

    @Test void T_11_21_T_11_25_modulesDisabledTemplatesAndSystemCodes() {
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        subscribe(CLUB, "subscription-marc", "account-marc");
        // SMS off → the SMS deliveries are SKIPPED_MODULE_OFF (the notice is still stored); PUSH off → SKIPPED_MODULE_OFF.
        modules(CLUB, Module.WAITLIST, Module.FREE_TRAINING);
        deliver(CLUB, "ClassCancelledByClub", Map.of("classId", "e7t02-class-a", "reason", "DELETED", "adminText", "x", "affected", affected("member-marc", "dog-ares", "b3")));
        assertThat(channels(stored(CLUB, "N-08a").stream().filter(n -> n.audience() == MEMBER).findFirst().orElseThrow()))
                .containsExactly("APP:DELIVERED", "EMAIL:SENT", "SMS:SKIPPED_MODULE_OFF");
        assertThat(sms.messages()).isEmpty();
        ports.activeBookings.put("booking-1", Instant.parse("2026-10-08T16:50:00Z"));
        deliver(CLUB, "ReminderDue", Map.of("bookingId", "booking-1", "memberId", "member-marc", "dogId", "dog-ares", "startsAt", "2026-10-08T16:50:00Z"));
        assertThat(channels(only(CLUB, "N-13"))).contains("PUSH:SKIPPED_MODULE_OFF"); assertThat(push.sent()).isEmpty();
        // A code whose module is off never exists (N-49 needs SMS), whatever its event.
        deliver(CLUB, "SmsCapReached", Map.of("month", "2026-10", "cap", 1000));
        assertThat(stored(CLUB, "N-49")).isEmpty();
        // A DISABLED template: no notification at all.
        try (var tenant = TenantContext.open(CLUB)) {
            var seed = templates.forCode(NotificationCatalog.byCode("N-37").orElseThrow(), "ca");
            mongo.updateFirst(Query.query(Criteria.where("_id").is(seed.id())), new Update().set("status", TemplateStatus.DISABLED.name()).set("enabled", false), MessageTemplate.class);
        }
        deliver(CLUB, "DogRegistered", Map.of("memberId", "member-marc", "dogId", "dog-ares"));
        assertThat(stored(CLUB, "N-37")).isEmpty();
        // T-11-25: a SYSTEM code (N-25) is never the engine's: no template, no notification (its e-mail is the SYSTEM path's).
        deliver(CLUB, "MagicLinkRequested", Map.of("accountId", "account-marc"));
        assertThat(stored(CLUB, "N-25")).isEmpty();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-25")), MessageTemplate.class)).isZero();
        // A platform event (no club) and an unknown club are nothing.
        deliver("event-platform", null, "DogRegistered", Map.of("memberId", "member-marc"));
        deliver("club-missing", "DogRegistered", Map.of("memberId", "member-marc"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is("club-missing")), Notification.class)).isZero();
    }

    @Test void T_11_11_bouncedAddressesAreSkippedAndAllBouncedIsNoContactAndTheBounceIsN51ToTheAdmins() {
        ports.update("member-anna", c -> InMemoryMessagingPorts.with(c, null, null,
                List.of(new MemberContact.Email("anna@example.test", true), new MemberContact.Email("anna.work@example.test", false)), null));
        deliver(CLUB, "DogLevelChanged", Map.of("memberId", "member-anna", "dogId", "dog-lluna", "levelName", "C"));
        assertThat(only(CLUB, "N-09").deliveries()).filteredOn(d -> d.channel() == NotificationChannel.EMAIL).extracting(Notification.Delivery::target, Notification.Delivery::status)
                .containsExactly(tuple("anna.work@example.test", DeliveryStatus.SENT));
        ports.markEmailBounced("member-anna", "anna.work@example.test");
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        deliver(CLUB, "DogLevelChanged", Map.of("memberId", "member-anna", "dogId", "dog-lluna", "levelName", "C"));
        assertThat(channels(only(CLUB, "N-09"))).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_NO_CONTACT");
        // The account's own login address marked by E1-T03 (decision E12) counts as bounced too (Marc's only contact address is that one).
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, null, null, List.of(new MemberContact.Email(accountEmail("account-marc"), false)), null));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("account-marc")), new Update().set("emailStatus", "BOUNCED"), "accounts");
        deliver(CLUB, "DogLevelChanged", Map.of("memberId", "member-marc", "dogId", "dog-ares", "levelName", "D"));
        assertThat(channels(stored(CLUB, "N-09").stream().filter(n -> "member-marc".equals(n.recipient().memberId())).findFirst().orElseThrow()))
                .containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_NO_CONTACT");
        // EmailBounced → N-51 to the admins (APP), with the member's name and the address, action OPEN_MEMBER.
        deliver(CLUB, "EmailBounced", Map.of("memberId", "member-anna", "email", "anna.work@example.test", "type", "BOUNCE"));
        assertThat(stored(CLUB, "N-51")).hasSize(2).allSatisfy(n -> {
            assertThat(n.audience()).isEqualTo(ADMINS); assertThat(channels(n)).containsExactly("APP:DELIVERED");
            assertThat(n.body()).contains("Anna Soler", "anna.work@example.test"); assertThat(n.action().params()).containsEntry("memberId", "member-anna");
        });
    }

    @Test void T_11_23_clubNewsMailsCarryTheSignedUnsubscribeLinkAndTransactionalOnesNever() {
        deliver(CLUB, "AnnouncementSent", Map.of("batchId", "batch-1", "memberId", "member-laura"));
        var news = only(CLUB, "N-24");
        assertThat(news.dedupKey()).isEqualTo("batch-1:member-laura");
        assertThat(channels(news)).containsExactly("APP:DELIVERED", "EMAIL:SENT", "PUSH:SKIPPED_NO_CONTACT");
        var mailToLaura = mail.to("laura@example.test").getFirst();
        String header = mailToLaura.headers().get("List-Unsubscribe");
        assertThat(header).startsWith("<https://engine.example.test/comunicats/baixa?t=").endsWith(">").doesNotContain("laura@");
        String token = header.substring(header.indexOf("?t=") + 3, header.length() - 1);
        assertThat(unsubscribes.verify(token, CLUB)).isEqualTo(new UnsubscribeTokens.Claim(CLUB, "member-laura"));
        assertThat(mailToLaura.html()).contains("Deixar de rebre aquests comunicats"); assertThat(mailToLaura.text()).contains("Deixar de rebre aquests comunicats: https://engine.example.test/");
        // A PERSONAL notice (N-09): no header, no footer.
        deliver(CLUB, "DogLevelChanged", Map.of("memberId", "member-laura", "dogId", "dog-duna", "levelName", "C"));
        assertThat(mail.to("laura@example.test")).hasSize(2).last().satisfies(m -> { assertThat(m.headers()).isEmpty(); assertThat(m.html()).doesNotContain("Deixar de rebre"); });
        assertThat(only(CLUB, "N-09").title()).isEqualTo("La Duna puja de nivell!");
        // After «Deixar de rebre» (CLUB_NEWS e-mail off) and with push of club news off: APP only, both skipped by preference.
        subscribe(CLUB, "subscription-laura", "account-laura");
        ports.update("member-laura", c -> InMemoryMessagingPorts.with(c, null, Map.of("emailByCategory", Map.of("CLUB_NEWS", false), "pushClubNews", false), null, null));
        deliver(CLUB, "AnnouncementSent", Map.of("batchId", "batch-2", "memberId", "member-laura"));
        assertThat(channels(stored(CLUB, "N-24").stream().filter(n -> n.dedupKey().startsWith("batch-2")).findFirst().orElseThrow()))
                .containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_BY_PREFERENCE", "PUSH:SKIPPED_BY_PREFERENCE");
        assertThat(push.sent()).isEmpty();
    }

    @Test void R_11_17_S11_7_parameterConditionsOwnerExceptionsEmitAndAFailedInlineSend() {
        // N-33 only with messaging.notifyWeekOpening (false by default).
        deliver(CLUB, "WeekOpened", Map.of("memberId", "member-laura", "weekStart", "2026-10-12"));
        assertThat(stored(CLUB, "N-33")).isEmpty();
        parameter(CLUB, "messaging.notifyWeekOpening", true, "bool");
        deliver(CLUB, "WeekOpened", Map.of("memberId", "member-laura", "weekStart", "2026-10-12"));
        assertThat(only(CLUB, "N-33").body()).contains("12 d’octubre de 2026");
        // N-31 only with messaging.notifyNewRingSetup, an ACTIVE setup and COURSES.
        parameter(CLUB, "messaging.notifyNewRingSetup", true, "bool");
        deliver(CLUB, "RingSetupChanged", Map.of("memberId", "member-laura", "status", "ACTIVE", "ringName", "Central"));
        assertThat(stored(CLUB, "N-31")).isEmpty(); // COURSES is off
        modules(CLUB, Module.WAITLIST, Module.PUSH, Module.SMS, Module.BILLING, Module.COURSES);
        deliver(CLUB, "RingSetupChanged", Map.of("memberId", "member-laura", "status", "DRAFT", "ringName", "Central"));
        assertThat(stored(CLUB, "N-31")).isEmpty();
        deliver(CLUB, "RingSetupChanged", Map.of("memberId", "member-laura", "status", "ACTIVE", "ringName", "Central"));
        assertThat(stored(CLUB, "N-31")).hasSize(1);
        // InvoiceFailed: N-10 to the admins, plus N-35 to the member only with Stripe; N-30 with Stripe only without the Stripe receipt.
        deliver(CLUB, "InvoiceFailed", Map.of("memberId", "member-laura", "provider", "SEPA_XML"));
        assertThat(stored(CLUB, "N-10")).hasSize(2); assertThat(stored(CLUB, "N-35")).isEmpty();
        deliver(CLUB, "InvoiceFailed", Map.of("memberId", "member-laura", "provider", "STRIPE"));
        assertThat(stored(CLUB, "N-35")).hasSize(1);
        deliver(CLUB, "InvoicePaid", Map.of("memberId", "member-laura", "provider", "STRIPE"));
        assertThat(stored(CLUB, "N-30")).isEmpty();
        deliver(CLUB, "InvoicePaid", Map.of("memberId", "member-laura", "provider", "SEPA_XML"));
        assertThat(stored(CLUB, "N-30")).hasSize(1);
        parameter(CLUB, "billing.stripeReceiptEmail", false, "bool");
        deliver(CLUB, "InvoicePaid", Map.of("memberId", "member-laura", "provider", "STRIPE"));
        assertThat(stored(CLUB, "N-30")).hasSize(2);

        // Owner exceptions: the Annex A variant (N-32b from the back office → CLUB_CHANGES, its caps as channels), a channel the event
        // switches on (N-32a `notifyEmail`), a channel the owner sends itself (N-02's credential e-mail).
        var exceptions = new com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort() {
            public java.util.Set<String> eventTypes() { return java.util.Set.of("ActivityRegistrationChanged", "ActivityPublished", "MemberValidated"); }
            public java.util.Optional<com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts> facts(
                    com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger trigger, String code) {
                var builder = com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts.builder().member("member-laura", null)
                        .value("activity_title", "Seminari").value("date", "2026-10-12");
                return java.util.Optional.of(switch (code) {
                    case "N-32b" -> builder.category("CLUB_CHANGES").build();
                    case "N-32a" -> builder.enable("EMAIL").build();
                    default -> builder.exclude("EMAIL").build();
                });
            }
        };
        modules(CLUB, Module.WAITLIST, Module.PUSH, Module.SMS, Module.ACTIVITIES);
        engine = new NotificationEngine(configs, messages, templates, new RecipientResolver(ports, ports, ports), notifications, subscriptions, accounts, List.of(exceptions),
                ports, events, dispatcher, clock, transactions);
        deliver(CLUB, "ActivityRegistrationChanged", Map.of("memberId", "member-laura"));
        var variant = only(CLUB, "N-32b");
        assertThat(variant.category()).isEqualTo(com.agilityhub.core.clubs.messaging.domain.NotificationCategory.CLUB_CHANGES);
        assertThat(channels(variant)).containsExactly("APP:DELIVERED", "EMAIL:SENT", "SMS:SENT", "SMS:SENT");
        deliver(CLUB, "ActivityPublished", Map.of("memberId", "member-laura"));
        assertThat(channels(only(CLUB, "N-32a"))).containsExactly("APP:DELIVERED", "EMAIL:SENT");
        deliver(CLUB, "MemberValidated", Map.of("memberId", "member-laura"));
        assertThat(channels(only(CLUB, "N-02"))).containsExactly("APP:DELIVERED");

        // `emit` (an owner's derived notice inside its own outbox delivery, S15 R-15-11) runs the same pipeline and sends after the commit.
        var emitting = new NotificationEngine(configs, messages, templates, new RecipientResolver(ports, ports, ports), notifications, subscriptions, accounts, owners, ports,
                events, dispatcher, clock, transactions);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> emitting.emit(
                new com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger("emitted-a", "DogLevelChanged", CLUB, "Dog", "dog-ares", clock.instant(),
                        Map.of("memberId", "member-marc", "dogId", "dog-ares", "levelName", "D"), null, null, com.agilityhub.core.shared.domain.DomainEvent.Origin.SYSTEM)));
        assertThat(channels(stored(CLUB, "N-09").stream().filter(n -> "emitted-a".equals(n.eventId())).findFirst().orElseThrow())).containsExactly("APP:DELIVERED", "EMAIL:SENT");
        // A failing inline send leaves the deliveries QUEUED for the 5-second poll (the event itself is processed).
        var broken = org.mockito.Mockito.mock(NotificationDispatcher.class);
        org.mockito.Mockito.when(broken.dispatch(org.mockito.ArgumentMatchers.anyCollection())).thenThrow(new IllegalStateException("network"));
        engine = new NotificationEngine(configs, messages, templates, new RecipientResolver(ports, ports, ports), notifications, subscriptions, accounts, owners, ports,
                events, broken, clock, transactions);
        String failed = deliver(CLUB, "DogLevelChanged", Map.of("memberId", "member-anna", "dogId", "dog-lluna", "levelName", "C"));
        assertThat(stored(CLUB, "N-09").stream().filter(n -> failed.equals(n.eventId())).findFirst().orElseThrow().deliveries())
                .filteredOn(d -> d.channel() == NotificationChannel.EMAIL).extracting(Notification.Delivery::status).containsOnly(DeliveryStatus.QUEUED);
    }

    @Test void T_11_14_leftMembersAndTheDefaultRecipientsOfAnEventWithoutOwner() {
        ports.update("member-marc", c -> InMemoryMessagingPorts.with(c, "LEFT", null, null, null));
        // N-28 (LeaveResolved, no owner yet: the payload's member and scalar values) reaches the member who left.
        deliver(CLUB, "LeaveResolved", Map.of("memberId", "member-marc", "effectiveDate", "2026-10-31", "cancelledCount", 2));
        var n28 = only(CLUB, "N-28");
        assertThat(n28.recipient().memberId()).isEqualTo("member-marc"); assertThat(n28.locale()).isEqualTo("es");
        assertThat(n28.body()).contains("31 de octubre de 2026", "Club Agility Exemple", "Ares");
        assertThat(n28.variables()).containsEntry("effective_date", "31 de octubre de 2026").containsEntry("member_first_name", "Marc");
        // N-24 never reaches him.
        deliver(CLUB, "AnnouncementSent", Map.of("batchId", "batch-1", "memberId", "member-marc"));
        assertThat(stored(CLUB, "N-24")).isEmpty();
        // Time: the engine never uses the wall clock (the notices carry the injected instant).
        assertThat(n28.createdAt()).isEqualTo(clock.instant());
    }
}
