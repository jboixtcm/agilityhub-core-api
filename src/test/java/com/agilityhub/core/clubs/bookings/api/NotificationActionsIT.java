package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.activities.application.ActivityLifecycleService;
import com.agilityhub.core.clubs.activities.application.ActivityRegistrationService;
import com.agilityhub.core.clubs.activities.application.ActivityService;
import com.agilityhub.core.clubs.activities.domain.ActivityCancellationReason;
import com.agilityhub.core.clubs.activities.domain.ActivityType;
import com.agilityhub.core.clubs.bookings.application.AttendanceCaller;
import com.agilityhub.core.clubs.bookings.application.AttendanceSheetService;
import com.agilityhub.core.clubs.bookings.application.BookingActor;
import com.agilityhub.core.clubs.bookings.application.BookingCancellationService;
import com.agilityhub.core.clubs.bookings.application.BookingConfirmationService;
import com.agilityhub.core.clubs.bookings.application.SeatHoldService;
import com.agilityhub.core.clubs.bookings.application.WaitlistService;
import com.agilityhub.core.clubs.bookings.application.jobs.NoShowNoticesJob;
import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.census.application.DogService;
import com.agilityhub.core.clubs.common.application.RemindersJob;
import com.agilityhub.core.clubs.followup.application.TaskService;
import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.clubs.scheduling.application.ClassCancellationUseCase;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.clubs.scheduling.application.RingBlockService;
import com.agilityhub.core.clubs.scheduling.application.RiskReviewJob;
import com.agilityhub.core.clubs.training.application.TrainingActor;
import com.agilityhub.core.clubs.training.application.TrainingBookingService;
import com.agilityhub.core.clubs.training.domain.TrainingCancelledBy;
import com.agilityhub.core.clubs.training.domain.TrainingOrigin;
import com.agilityhub.core.platform.application.jobs.JobRunner;
import com.agilityhub.core.shared.application.DemoSeedActor;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T04 step 3, the second matrix (T-11-02 completed, T-11-24): the real E5/E6 actions driven through their application
 * services on the fictional S08 club, the outbox dispatched to the real S11 engine and its senders' doubles, and the resulting
 * notices asserted end to end — code, audience, recipient, every delivery with its status, and the title (and a fragment of
 * the body) rendered in the recipient's own language: Laura in Catalan, Joan in Spanish, Pere in English, the admin in Catalan
 * and the instructor Estela in Spanish (so every group has a recipient in `es` or `en`). Pere has one push device. Each case
 * starts from the fixture, runs its preparation (whose notices are dropped), then its action.
 */
class NotificationActionsIT extends BookingFixtures {
    @Autowired SeatHoldService holds; @Autowired BookingConfirmationService confirmations; @Autowired BookingCancellationService cancellations;
    @Autowired WaitlistService waitlist; @Autowired TrainingBookingService trainings; @Autowired ClassCancellationUseCase classCancellations;
    @Autowired ClassSessionService classes; @Autowired AttendanceSheetService attendance; @Autowired TaskService tasks; @Autowired DogService dogs;
    @Autowired ActivityService activities; @Autowired ActivityLifecycleService lifecycle; @Autowired ActivityRegistrationService registrations;
    @Autowired JobRunner runner; @Autowired RiskReviewJob riskReview; @Autowired NoShowNoticesJob noShows; @Autowired RemindersJob reminders;

    enum Action {
        MEMBER_BOOKS, MEMBER_CANCELS, CLUB_BOOKS_AND_CANCELS, TRAINING_BOOKED_AND_CANCELLED, TRAINING_BY_THE_CLUB, SEAT_RELEASED, CLASS_CANCELLED_BY_THE_CLUB,
        CLASS_MODIFIED, CLASS_AT_RISK_AND_AUTO_CANCELLED, BELOW_THE_MINIMUM, NO_SHOW, TASK_CREATED_AND_COMPLETED, MEMBER_NOTE, ACTIVITY_LIFECYCLE, REMINDER
    }

    @BeforeEach void languages() {
        for (var entry : Map.of("s08-joan", "es", "s08-pere", "en", "s08-inst", "es").entrySet()) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(entry.getKey())), new Update().set("locale", entry.getValue()), "accounts");
        }
        // Free training for level C (Duna, Toby, Nit); Pere's phone has the PWA installed.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-lv-C")), new Update().set("grantsFreeTraining", true), "levels");
        var keys = new PushSubscription.Keys(com.agilityhub.core.clubs.messaging.support.PushKeyFixtures.p256dh(), com.agilityhub.core.clubs.messaging.support.PushKeyFixtures.auth());
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "push_subscriptions");
        mongo.insert(new PushSubscription("s08-sub-pere", CLUB, "s08-pere", "https://push.example.test/s08-pere", null, keys, "Android · Chrome", "UA",
                PushSubscription.Status.ACTIVE, 0, null, null, 0L, clock.instant(), "s08-pere", clock.instant(), "s08-pere"));
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs"); mongo.remove(new Query(), "job_locks");
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "activities"); mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "activity_registrations");
    }

    @ParameterizedTest(name = "{0}") @EnumSource(Action.class)
    void T_11_02_T_11_24_everyRealE5E6ActionNotifiesItsCodesEndToEnd(Action action) {
        switch (action) {
            case MEMBER_BOOKS -> {
                act(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                expect("N-04", "N-04 MEMBER s08-pere en «Booking confirmed» [APP:DELIVERED]");
                body("N-04", "s08-pere", "with Nit");
            }
            case MEMBER_CANCELS -> {
                exempt("thu");
                String nit = prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                act(() -> cancel(member("s08-pere", "s08-m-pere", "Pere"), nit));
                expect("N-05", "N-05 MEMBER s08-pere en «Booking cancelled» [APP:DELIVERED]");
                assertThat(lines("N-54")).isEmpty();
            }
            case CLUB_BOOKS_AND_CANCELS -> {
                exempt("thu");
                var club = new BookingActor("s08-admin", "s08-m-joan", "Admin", "s08-m-joan", BookingOrigin.BACKOFFICE, ActorRole.ADMIN);
                String toby = act(() -> book(club, "thu", "s08-d-toby"));
                act(() -> cancel(club, toby));
                expect("N-36", "N-36 MEMBER s08-joan es «Reserva gestionada por el club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]",
                        "N-36 MEMBER s08-joan es «Reserva gestionada por el club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]");
                assertThat(lines("N-04")).isEmpty(); assertThat(lines("N-05")).isEmpty();
            }
            case TRAINING_BOOKED_AND_CANCELLED -> {
                String nit = act(() -> as("s08-pere", "MEMBER", () -> trainings.book(TrainingActor.member("s08-m-pere"), "s08-d-nit", local("2026-10-07T10:00"), "s08-ring",
                        null, UUID.randomUUID().toString()).booking().id()));
                act(() -> as("s08-pere", "MEMBER", () -> trainings.cancel(nit, TrainingActor.member("s08-m-pere"), null)));
                expect("N-06", "N-06 MEMBER s08-pere en «Booking confirmed» [APP:DELIVERED]");
                expect("N-07", "N-07 MEMBER s08-pere en «Training cancelled» [APP:DELIVERED]");
                body("N-06", "s08-pere", "Central");
            }
            case TRAINING_BY_THE_CLUB -> {
                var club = new TrainingActor("s08-admin", "s08-m-joan", "Admin", "s08-m-joan", TrainingOrigin.BACKOFFICE, TrainingCancelledBy.MEMBER);
                String toby = act(() -> as("s08-admin", "ADMIN", () -> trainings.book(club, "s08-d-toby", local("2026-10-07T11:00"), "s08-ring", null,
                        UUID.randomUUID().toString()).booking().id()));
                act(() -> as("s08-admin", "ADMIN", () -> trainings.cancel(toby, TrainingActor.club(), "Pista ocupada")));
                expect("N-06", "N-06 MEMBER s08-joan es «Reserva confirmada» [APP:DELIVERED]");
                expect("N-47", "N-47 MEMBER s08-joan es «Entrenamiento gestionado por el club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]",
                        "N-47 MEMBER s08-joan es «Entrenamiento gestionado por el club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]");
                assertThat(lines("N-07")).isEmpty();
            }
            case SEAT_RELEASED -> {
                exempt("last");
                String duna = prepare(() -> book("s08-laura", "s08-m-laura", "Laura", "last", "s08-d-duna"));
                prepare(() -> as("s08-pere", "MEMBER", () -> waitlist.join(member("s08-pere", "s08-m-pere", "Pere"), "s08-last", "s08-d-nit").id()));
                act(() -> cancel(member("s08-laura", "s08-m-laura", "Laura"), duna));
                expect("N-15", "N-15 MEMBER s08-pere en «A place has been freed up!» [APP:DELIVERED, PUSH:SENT, SMS:SENT]");
                assertThat(only("N-15").action().type().name()).isEqualTo("CLAIM_SEAT");
                expect("N-05", "N-05 MEMBER s08-laura ca «Reserva anul·lada» [APP:DELIVERED]");
            }
            case CLASS_CANCELLED_BY_THE_CLUB -> {
                prepare(() -> book("s08-laura", "s08-m-laura", "Laura", "fri", "s08-d-duna"));
                prepare(() -> book("s08-laura", "s08-m-laura", "Laura", "fri", "s08-d-rock"));
                prepare(() -> book("s08-joan", "s08-m-joan", "Joan", "fri", "s08-d-toby"));
                act(() -> as("s08-admin", "ADMIN", () -> { classCancellations.cancelByClub("s08-fri", "Plou massa: la classe queda anul·lada.", "s08-admin"); return null; }));
                // One per dog (Laura twice), the class's instructor with the staff copy, the admins.
                expect("N-08a", "N-08a ADMINS s08-admin ca «Classe anul·lada pel club» [APP:DELIVERED]",
                        "N-08a INSTRUCTORS s08-inst es «Clase cancelada por el club» [APP:DELIVERED, EMAIL:SENT]",
                        "N-08a MEMBER s08-joan es «Clase cancelada por el club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]",
                        "N-08a MEMBER s08-laura ca «Classe anul·lada pel club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]",
                        "N-08a MEMBER s08-laura ca «Classe anul·lada pel club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]");
                body("N-08a", "s08-joan", "Plou massa");
            }
            case CLASS_MODIFIED -> {
                prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "sat", "s08-d-nit"));
                long version = session("sat").get("version", Number.class).longValue();
                // With levels on, a saved class names its levels (LEVEL_REQUIRED): C, Nit's. Only the start time is a change N-08b tells.
                act(() -> as("s08-admin", "ADMIN", () -> classes.patch("s08-sat", version, Map.of("startTime", "09:30", "levelIds", List.of("s08-lv-C")), false)));
                expect("N-08b", "N-08b INSTRUCTORS s08-inst es «Clase modificada por el club» [APP:DELIVERED]",
                        "N-08b MEMBER s08-pere en «Class changed by the club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]");
            }
            case CLASS_AT_RISK_AND_AUTO_CANCELLED -> {
                prepare(() -> book("s08-joan", "s08-m-joan", "Joan", "wed", "s08-d-toby"));
                prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                clock.setInstant(local("2026-10-07T07:30"));
                act(() -> runner.scheduled(CLUB, true, riskReview, clock.instant()).orElseThrow());
                // Wednesday's class (today, 1 dog) is cancelled: N-17 to its instructor and the admins, N-08a to Joan only (RISK_REVIEW).
                expect("N-17", "N-17 ADMINS s08-admin ca «Classe anul·lada per manca d'alumnes» [APP:DELIVERED, EMAIL:SENT]",
                        "N-17 INSTRUCTORS s08-inst es «Clase cancelada por falta de alumnos» [APP:DELIVERED, EMAIL:SENT]");
                expect("N-08a", "N-08a MEMBER s08-joan es «Clase cancelada por el club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]");
                // Thursday's (1 dog): N-16 to Pere; the admins get one per class at risk in the window.
                assertThat(lines("N-16").stream().filter(l -> l.contains(" MEMBER ")).toList())
                        .containsExactly("N-16 MEMBER s08-pere en «Class may be cancelled» [APP:DELIVERED, EMAIL:SENT]");
                assertThat(lines("N-16").stream().filter(l -> !l.contains(" MEMBER ")).toList()).isNotEmpty()
                        .allMatch(l -> l.equals("N-16 ADMINS s08-admin ca «Possible anul·lació de classe» [APP:DELIVERED]"));
            }
            case BELOW_THE_MINIMUM -> {
                prepare(() -> book("s08-laura", "s08-m-laura", "Laura", "thu", "s08-d-duna"));
                String nit = prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                act(() -> cancel(member("s08-pere", "s08-m-pere", "Pere"), nit));
                // On time and below classes.minDogs (2 → 1): the class's instructor and the admins, APP + EMAIL, and nothing else.
                expect("N-54", "N-54 ADMINS s08-admin ca «Classe amb pocs alumnes» [APP:DELIVERED, EMAIL:SENT]",
                        "N-54 INSTRUCTORS s08-inst es «Clase con pocos alumnos» [APP:DELIVERED, EMAIL:SENT]");
                expect("N-05", "N-05 MEMBER s08-pere en «Booking cancelled» [APP:DELIVERED]");
                assertThat(lines("N-08a")).isEmpty(); assertThat(lines("N-16")).isEmpty();
                assertThat(session("thu").getString("state")).isEqualTo("ACTIVE");
            }
            case NO_SHOW -> {
                String toby = prepare(() -> book("s08-joan", "s08-m-joan", "Joan", "wed", "s08-d-toby"));
                clock.setInstant(local("2026-10-07T20:00"));
                prepare(() -> as("s08-inst", "INSTRUCTOR", () -> attendance.save("s08-wed", 0, List.of(new AttendanceSheetService.Item(toby, AttendanceState.NO_SHOW)),
                        new AttendanceCaller("s08-inst", "Estela", false, "s08-instructor"))));
                clock.setInstant(local("2026-10-08T08:00"));
                act(() -> runner.scheduled(CLUB, true, noShows, clock.instant()).orElseThrow());
                expect("N-19", "N-19 MEMBER s08-joan es «Te hemos echado de menos» [APP:DELIVERED, EMAIL:SENT]");
                // The class date written in full, never «ahir» (S10 §8, T-11-33, ruling E69).
                body("N-19", "s08-joan", "7 de octubre de 2026");
            }
            case TASK_CREATED_AND_COMPLETED -> {
                var task = act(() -> as("s08-inst", "INSTRUCTOR", () -> tasks.create("s08-d-nit", "Treballar el contacte a la zona de salts", List.of(),
                        new Task.Actor("s08-inst", AuthorRole.INSTRUCTOR, "Estela"))));
                act(() -> as("s08-pere", "MEMBER", () -> tasks.complete(task, new Task.Actor("s08-pere", AuthorRole.MEMBER, "Pere", "MALE"))));
                expect("N-20", "N-20 MEMBER s08-pere en «New task for Nit» [APP:DELIVERED, EMAIL:SENT]");
                expect("N-21", "N-21 INSTRUCTORS s08-inst es «Tarea completada» [APP:DELIVERED]");
            }
            case MEMBER_NOTE -> {
                act(() -> as("s08-pere", "MEMBER", () -> { dogs.note("s08-d-nit", "Té una mica de por dels túnels."); return null; }));
                expect("N-22", "N-22 INSTRUCTORS s08-inst es «Nota nueva del alumno» [APP:DELIVERED]");
            }
            case ACTIVITY_LIFECYCLE -> {
                var created = prepare(() -> as("s08-admin", "ADMIN", () -> {
                    var draft = activities.create(Map.of("ca", "Seminari de salts", "es", "Seminario de saltos", "en", "Jumping seminar"), ActivityType.SEMINAR);
                    var patch = new LinkedHashMap<String, Object>();
                    patch.put("date", LocalDate.parse("2026-10-17")); patch.put("startTime", "10:00"); patch.put("endTime", "13:00");
                    patch.put("registrationFrom", LocalDate.parse("2026-10-06")); patch.put("registrationTo", LocalDate.parse("2026-10-15"));
                    patch.put("maxPlaces", 10); patch.put("waitlistEnabled", false); patch.put("levelIds", List.of()); patch.put("ringIds", List.of());
                    return activities.patch(draft.id(), draft.version(), patch, new RingBlockService.Options(false, false, null));
                }));
                var published = act(() -> as("s08-admin", "ADMIN", () -> lifecycle.publish(created.id(), true, new RingBlockService.Options(false, false, null))));
                act(() -> as("s08-pere", "MEMBER", () -> registrations.register(created.id(), false)));
                var current = mongo.findById(created.id(), Document.class, "activities");
                act(() -> as("s08-admin", "ADMIN", () -> activities.patch(published.id(), current.get("version", Number.class).longValue(), Map.of("startTime", "10:30"),
                        new RingBlockService.Options(false, false, null))));
                act(() -> as("s08-admin", "ADMIN", () -> lifecycle.cancel(created.id(), ActivityCancellationReason.CLUB_MANUAL, "Suspès per la pluja.")));
                assertThat(lines("N-32a").stream().filter(l -> l.contains(" s08-pere ")).toList())
                        .containsExactly("N-32a MEMBER s08-pere en «Activity published» [APP:DELIVERED, EMAIL:SENT]");
                assertThat(lines("N-32a")).contains("N-32a MEMBER s08-joan es «Actividad publicada» [APP:DELIVERED, EMAIL:SENT]");
                assertThat(lines("N-32b").stream().filter(l -> l.contains(" s08-pere ")).toList()).isNotEmpty()
                        .allMatch(l -> l.equals("N-32b MEMBER s08-pere en «Activity registration» [APP:DELIVERED]"));
                expect("N-32d", "N-32d MEMBER s08-pere en «Activity changed by the club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]");
                expect("N-32c", "N-32c MEMBER s08-pere en «Activity cancelled by the club» [APP:DELIVERED, EMAIL:SENT, SMS:SENT]");
                body("N-32c", "s08-pere", "Jumping seminar");
            }
            case REMINDER -> {
                mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-pere")), new Update().set("notificationPreferences", new Document("reminderMinutesBefore", 60)), "members");
                prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                clock.setInstant(local("2026-10-08T17:50"));
                act(() -> runner.scheduled(CLUB, true, reminders, clock.instant()).orElseThrow());
                expect("N-13", "N-13 MEMBER s08-pere en «Class reminder» [APP:DELIVERED, EMAIL:SKIPPED_BY_PREFERENCE, PUSH:SENT]");
                body("N-13", "s08-pere", "with Nit");
            }
        }
        // No notice is left half-rendered: every variable of every notice of the action has its value.
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class))
                .allSatisfy(n -> { assertThat(n.title()).doesNotContain("[["); assertThat(n.body()).doesNotContain("[["); });
    }

    // ---- the actions

    BookingActor member(String accountId, String memberId, String name) { return new BookingActor(accountId, memberId, name, null, BookingOrigin.APP, ActorRole.MEMBER); }
    String book(String accountId, String memberId, String name, String classId, String dogId) { return book(member(accountId, memberId, name), classId, dogId); }
    /** Hold + confirm (S08 R-08-05/06) as the actor: the member from the app, or the admin from the back office on the member's behalf. */
    String book(BookingActor actor, String classId, String dogId) {
        return as(actor.accountId(), role(actor), () -> {
            var held = holds.hold(actor, "s08-" + classId, dogId, null);
            return confirmations.confirm(actor, held.hold().id(), null).booking().id();
        });
    }
    String cancel(BookingActor actor, String bookingId) {
        return as(actor.accountId(), role(actor), () -> cancellations.cancel(bookingId, actor, actor.role() == ActorRole.ADMIN ? "Canvi de grup" : null).id());
    }
    static String role(BookingActor actor) { return actor.role() == ActorRole.ADMIN ? "ADMIN" : "MEMBER"; }
    void exempt(String classId) { mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-" + classId)), new Update().set("risk.exempt", true), "class_sessions"); }
    static <T> T as(String accountId, String role, Supplier<T> work) { return DemoSeedActor.as(accountId, role, work); }

    /** A preparation step: its notices are not the case's (dispatched, then dropped). */
    <T> T prepare(Supplier<T> step) {
        T result = act(step);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "notifications");
        return result;
    }
    /** The action in the club, then the outbox: the engine stores and sends right after each commit. */
    <T> T act(Supplier<T> step) {
        T result;
        try (var tenant = TenantContext.open(CLUB)) { result = step.get(); }
        dispatch();
        return result;
    }

    // ---- the assertions

    /** «code audience account locale «title» [channel:status…]», one per notification of the code, sorted. */
    List<String> lines(String code) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)), Notification.class).stream().map(n -> n.code() + " " + n.audience() + " "
                + (n.recipient() == null ? "-" : n.recipient().accountId()) + " " + n.locale() + " «" + n.title() + "» "
                + n.deliveries().stream().map(d -> d.channel() + ":" + d.status()).sorted().toList()).sorted().toList();
    }
    void expect(String code, String... notices) { assertThat(lines(code)).as(code).containsExactlyInAnyOrder(notices); }
    Notification only(String code) {
        var found = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)), Notification.class);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }
    void body(String code, String accountId, String fragment) {
        var found = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code).and("recipient.accountId").is(accountId)), Notification.class);
        assertThat(found).isNotEmpty().allSatisfy(n -> assertThat(n.body()).as(code + " " + accountId).contains(fragment));
    }
}
