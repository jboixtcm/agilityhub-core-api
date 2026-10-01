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
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
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
 * notices asserted end to end — code, audience, recipient, every delivery with its status, and the title and the body rendered
 * in the recipient's own language: Laura in Catalan, Joan in Spanish, Pere in English, the admin in Catalan and the instructor
 * Estela in Spanish (so every group has a recipient in `es` or `en`). Pere has one push device. Each case starts from the
 * fixture, runs its preparation (whose notices are dropped), then its action.
 *
 * <p>E7-T07 (review #3 of E7-T04 round 3): every case asserts the complete set of notices its action leaves in the club — no
 * missing recipient, no extra one, no duplicate, of any code — each with its whole rendered body (the template's text in the
 * recipient's language with every variable's value; the staff copies are the `notif.{code}.staff.*` messages), never a
 * fragment.</p>
 */
class NotificationActionsIT extends BookingFixtures {
    static final String PERE_DEVICE = "s08-sub-pere";
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
        mongo.insert(new PushSubscription(PERE_DEVICE, CLUB, "s08-pere", "https://push.example.test/s08-pere", null, keys, "Android · Chrome", "UA",
                PushSubscription.Status.ACTIVE, 0, null, null, 0L, clock.instant(), "s08-pere", clock.instant(), "s08-pere"));
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs"); mongo.remove(new Query(), "job_locks");
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "activities"); mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "activity_registrations");
    }
    /** E7-T04 round 2: Pere's device is this IT's only; the S08 ITs after it (`WeekOpeningJobIT`'s N-33 PUSH) never see it. */
    @AfterEach void removePeresDevice() { mongo.remove(Query.query(Criteria.where("_id").is(PERE_DEVICE)), "push_subscriptions"); }

    static final String APP = "APP:DELIVERED", EMAIL = "EMAIL:SENT", SMS = "SMS:SENT", PUSH = "PUSH:SENT";

    @ParameterizedTest(name = "{0}") @EnumSource(Action.class)
    void T_11_02_T_11_24_everyRealE5E6ActionNotifiesItsCodesEndToEnd(Action action) {
        switch (action) {
            case MEMBER_BOOKS -> {
                act(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                // N-04 «[[class_description]] class · [[class_date]] · [[class_time]] · [[ring_name]] · with [[dog_name]].»; Thursday is two days away.
                expect(notice("N-04", "MEMBER", "s08-pere", "en", "Booking confirmed", "Classe thu class · 8 Thursday · 18:50 · Central · with Nit.", APP));
            }
            case MEMBER_CANCELS -> {
                exempt("thu");
                String nit = prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                act(() -> cancel(member("s08-pere", "s08-m-pere", "Pere"), nit));
                // On time (`late` = false), and the class is exempt from the minimum: no N-54.
                expect(notice("N-05", "MEMBER", "s08-pere", "en", "Booking cancelled", "8 Thursday · 18:50, with Nit. You cancelled it in time.", APP));
            }
            case CLUB_BOOKS_AND_CANCELS -> {
                exempt("thu");
                var club = new BookingActor("s08-admin", "s08-m-joan", "Admin", "s08-m-joan", BookingOrigin.BACKOFFICE, ActorRole.ADMIN);
                String toby = act(() -> book(club, "thu", "s08-d-toby"));
                act(() -> cancel(club, toby));
                // N-36 (never N-04/N-05), `[[actor]]` = notif.N-36.actor «el club», at the start of the sentence.
                expect(notice("N-36", "MEMBER", "s08-joan", "es", "Reserva gestionada por el club", "El club te ha reservado una clase: jueves 8 · 18:50, con Toby.", APP, EMAIL, SMS),
                        notice("N-36", "MEMBER", "s08-joan", "es", "Reserva gestionada por el club", "El club te ha cancelado una clase: jueves 8 · 18:50, con Toby.", APP, EMAIL, SMS));
            }
            case TRAINING_BOOKED_AND_CANCELLED -> {
                String nit = act(() -> as("s08-pere", "MEMBER", () -> trainings.book(TrainingActor.member("s08-m-pere"), "s08-d-nit", local("2026-10-07T10:00"), "s08-ring",
                        null, UUID.randomUUID().toString()).booking().id()));
                act(() -> as("s08-pere", "MEMBER", () -> trainings.cancel(nit, TrainingActor.member("s08-m-pere"), null)));
                expect(notice("N-06", "MEMBER", "s08-pere", "en", "Booking confirmed", "Free training · tomorrow · 10:00–10:30 · Central · with Nit.", APP),
                        notice("N-07", "MEMBER", "s08-pere", "en", "Training cancelled", "Free training cancelled · tomorrow · 10:00–10:30 · Central · with Nit.", APP));
            }
            case TRAINING_BY_THE_CLUB -> {
                var club = new TrainingActor("s08-admin", "s08-m-joan", "Admin", "s08-m-joan", TrainingOrigin.BACKOFFICE, TrainingCancelledBy.MEMBER);
                String toby = act(() -> as("s08-admin", "ADMIN", () -> trainings.book(club, "s08-d-toby", local("2026-10-07T11:00"), "s08-ring", null,
                        UUID.randomUUID().toString()).booking().id()));
                act(() -> as("s08-admin", "ADMIN", () -> trainings.cancel(toby, TrainingActor.club(), "Pista ocupada")));
                // The booking's N-06 and N-47 twice (booked, then cancelled with the admin's text); never N-07.
                expect(notice("N-06", "MEMBER", "s08-joan", "es", "Reserva confirmada", "Entrenamiento libre · mañana · 11:00–11:30 · Central · con Toby.", APP),
                        notice("N-47", "MEMBER", "s08-joan", "es", "Entrenamiento gestionado por el club",
                                "El club te ha reservado un entrenamiento: mañana · 11:00–11:30 · Central, con Toby.", APP, EMAIL, SMS),
                        notice("N-47", "MEMBER", "s08-joan", "es", "Entrenamiento gestionado por el club",
                                "El club te ha anulado un entrenamiento: mañana · 11:00–11:30 · Central, con Toby. Pista ocupada", APP, EMAIL, SMS));
            }
            case SEAT_RELEASED -> {
                exempt("last");
                String duna = prepare(() -> book("s08-laura", "s08-m-laura", "Laura", "last", "s08-d-duna"));
                prepare(() -> as("s08-pere", "MEMBER", () -> waitlist.join(member("s08-pere", "s08-m-pere", "Pere"), "s08-last", "s08-d-nit").id()));
                act(() -> cancel(member("s08-laura", "s08-m-laura", "Laura"), duna));
                // The club's waitlist mode is ALL_AT_ONCE: no `confirm_by`.
                expect(notice("N-15", "MEMBER", "s08-pere", "en", "A place has been freed up!",
                                "Classe last class · 8 Thursday · 20:00. You are on the waiting list: the place goes to whoever confirms first.", APP, PUSH, SMS),
                        notice("N-05", "MEMBER", "s08-laura", "ca", "Reserva anul·lada", "Dijous 8 · 20:00, amb Duna. L'has anul·lada dins el termini.", APP));
                assertThat(only("N-15").action().type().name()).isEqualTo("CLAIM_SEAT");
            }
            case CLASS_CANCELLED_BY_THE_CLUB -> {
                prepare(() -> book("s08-laura", "s08-m-laura", "Laura", "fri", "s08-d-duna"));
                prepare(() -> book("s08-laura", "s08-m-laura", "Laura", "fri", "s08-d-rock"));
                prepare(() -> book("s08-joan", "s08-m-joan", "Joan", "fri", "s08-d-toby"));
                act(() -> as("s08-admin", "ADMIN", () -> { classCancellations.cancelByClub("s08-fri", "Plou massa: la classe queda anul·lada.", "s08-admin"); return null; }));
                // One per dog (Laura twice), the class's instructor and the admins with the staff copy (notif.N-08a.staff.body).
                expect(notice("N-08a", "ADMINS", "s08-admin", "ca", "Classe anul·lada pel club",
                                "Example Agility Club: divendres 9 · 20:00 · Classe fri — classe anul·lada. Plou massa: la classe queda anul·lada.", APP),
                        notice("N-08a", "INSTRUCTORS", "s08-inst", "es", "Clase cancelada por el club",
                                "Example Agility Club: viernes 9 · 20:00 · Classe fri — clase cancelada. Plou massa: la classe queda anul·lada.", APP, EMAIL),
                        notice("N-08a", "MEMBER", "s08-joan", "es", "Clase cancelada por el club", "Viernes 9 · 20:00 · Classe fri, con Toby. «Plou massa: la classe queda "
                                + "anul·lada.» — Example Agility Club. Esta sesión no cuenta en tu cómputo.", APP, EMAIL, SMS),
                        notice("N-08a", "MEMBER", "s08-laura", "ca", "Classe anul·lada pel club", "Divendres 9 · 20:00 · Classe fri, amb Duna. «Plou massa: la classe queda "
                                + "anul·lada.» — Example Agility Club. Aquesta sessió no compta al teu còmput.", APP, EMAIL, SMS),
                        notice("N-08a", "MEMBER", "s08-laura", "ca", "Classe anul·lada pel club", "Divendres 9 · 20:00 · Classe fri, amb Rock. «Plou massa: la classe queda "
                                + "anul·lada.» — Example Agility Club. Aquesta sessió no compta al teu còmput.", APP, EMAIL, SMS));
            }
            case CLASS_MODIFIED -> {
                prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "sat", "s08-d-nit"));
                long version = session("sat").get("version", Number.class).longValue();
                // With levels on, a saved class names its levels (LEVEL_REQUIRED): C, Nit's. Only the start time is a change N-08b tells.
                act(() -> as("s08-admin", "ADMIN", () -> classes.patch("s08-sat", version, Map.of("startTime", "09:30", "levelIds", List.of("s08-lv-C")), false)));
                // `[[changes]]` = scheduling.change.startTime; the instructor reads the staff copy (notif.N-08b.staff.body).
                expect(notice("N-08b", "INSTRUCTORS", "s08-inst", "es", "Clase modificada por el club", "Sábado 10 · Classe sat: la clase ha cambiado. Hora: 09:00 → 09:30.", APP),
                        notice("N-08b", "MEMBER", "s08-pere", "en", "Class changed by the club",
                                "10 Saturday, with Nit: the class has changed. Time: 09:00 → 09:30. Check the app.", APP, EMAIL, SMS));
            }
            case CLASS_AT_RISK_AND_AUTO_CANCELLED -> {
                prepare(() -> book("s08-joan", "s08-m-joan", "Joan", "wed", "s08-d-toby"));
                prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                clock.setInstant(local("2026-10-07T07:30"));
                act(() -> runner.scheduled(CLUB, true, riskReview, clock.instant()).orElseThrow());
                // Wednesday's class (today, 1 dog) is cancelled: N-17 to its instructor and the admins, N-08a to Joan only with the
                // automatic text (scheduling.autoCancel.text). In the window, Thursday's three classes and Friday's are at risk: N-16 to
                // Pere (Thursday 18:50, 1 dog) and one staff copy per class to the admins (notif.N-16.staff.body), reviewed on its own day.
                expect(notice("N-17", "ADMINS", "s08-admin", "ca", "Classe anul·lada per manca d'alumnes",
                                "Example Agility Club: avui · 18:50 · Classe wed — classe anul·lada automàticament perquè no ha arribat al mínim (1 gos inscrit).", APP, EMAIL),
                        notice("N-17", "INSTRUCTORS", "s08-inst", "es", "Clase cancelada por falta de alumnos",
                                "Example Agility Club: hoy · 18:50 · Classe wed — clase cancelada automáticamente porque no ha llegado al mínimo (1 perro inscrito).", APP, EMAIL),
                        notice("N-08a", "MEMBER", "s08-joan", "es", "Clase cancelada por el club", "Hoy · 18:50 · Classe wed, con Toby. «No se ha llegado al mínimo de 2 perros: "
                                + "la clase queda cancelada. ¡Disculpad las molestias!» — Example Agility Club. Esta sesión no cuenta en tu cómputo.", APP, EMAIL, SMS),
                        notice("N-16", "MEMBER", "s08-pere", "en", "Class may be cancelled", "Tomorrow · 18:50 · Classe thu, with Nit: the class does not have enough students "
                                + "yet. If nobody else books before 7:30 tomorrow, the class will be cancelled. We suggest booking another one.", APP, EMAIL),
                        atRisk("demà · 17:40 · Classe levelD", "demà"), atRisk("demà · 18:50 · Classe thu", "demà"), atRisk("demà · 20:00 · Classe last", "demà"),
                        atRisk("divendres 9 · 20:00 · Classe fri", "divendres"));
            }
            case BELOW_THE_MINIMUM -> {
                prepare(() -> book("s08-laura", "s08-m-laura", "Laura", "thu", "s08-d-duna"));
                String nit = prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                act(() -> cancel(member("s08-pere", "s08-m-pere", "Pere"), nit));
                // On time and below classes.minDogs (2 → 1): the class's instructor and the admins, APP + EMAIL, and nothing else (no N-08a, no N-16).
                expect(notice("N-54", "ADMINS", "s08-admin", "ca", "Classe amb pocs alumnes",
                                "Example Agility Club: dijous 8 · 18:50 · Classe thu (Central) ha quedat per sota del mínim: 1 gos inscrit.", APP, EMAIL),
                        notice("N-54", "INSTRUCTORS", "s08-inst", "es", "Clase con pocos alumnos",
                                "Example Agility Club: jueves 8 · 18:50 · Classe thu (Central) ha quedado por debajo del mínimo: 1 perro inscrito.", APP, EMAIL),
                        notice("N-05", "MEMBER", "s08-pere", "en", "Booking cancelled", "8 Thursday · 18:50, with Nit. You cancelled it in time.", APP));
                assertThat(session("thu").getString("state")).isEqualTo("ACTIVE");
            }
            case NO_SHOW -> {
                String toby = prepare(() -> book("s08-joan", "s08-m-joan", "Joan", "wed", "s08-d-toby"));
                clock.setInstant(local("2026-10-07T20:00"));
                prepare(() -> as("s08-inst", "INSTRUCTOR", () -> attendance.save("s08-wed", 0, List.of(new AttendanceSheetService.Item(toby, AttendanceState.NO_SHOW)),
                        new AttendanceCaller("s08-inst", "Estela", false, "s08-instructor"))));
                clock.setInstant(local("2026-10-08T08:00"));
                act(() -> runner.scheduled(CLUB, true, noShows, clock.instant()).orElseThrow());
                // The class date written in full, never «ahir» (S10 §8, T-11-33, ruling E69).
                expect(notice("N-19", "MEMBER", "s08-joan", "es", "Te hemos echado de menos", "Miércoles, 7 de octubre de 2026 no pudiste venir a la clase de Classe wed. "
                        + "Recuerda que puedes anular desde la app hasta última hora: así otra persona puede aprovechar la clase. La sesión cuenta en tu cómputo.", APP, EMAIL));
            }
            case TASK_CREATED_AND_COMPLETED -> {
                var task = act(() -> as("s08-inst", "INSTRUCTOR", () -> tasks.create("s08-d-nit", "Treballar el contacte a la zona de salts", List.of(),
                        new Task.Actor("s08-inst", AuthorRole.INSTRUCTOR, "Estela"))));
                act(() -> as("s08-pere", "MEMBER", () -> tasks.complete(task, new Task.Actor("s08-pere", AuthorRole.MEMBER, "Pere", "MALE"))));
                expect(notice("N-20", "MEMBER", "s08-pere", "en", "New task for Nit", "Estela set you a task for Nit: “Treballar el contacte a la zona de salts”", APP, EMAIL),
                        notice("N-21", "INSTRUCTORS", "s08-inst", "es", "Tarea completada",
                                "La tarea de Nit (Pere Example, alumno) ya está hecha: «Treballar el contacte a la zona de salts»", APP));
            }
            case MEMBER_NOTE -> {
                act(() -> as("s08-pere", "MEMBER", () -> { dogs.note("s08-d-nit", "Té una mica de por dels túnels."); return null; }));
                // Pere has no gender in the census: the `other` form.
                expect(notice("N-22", "INSTRUCTORS", "s08-inst", "es", "Nota nueva del alumno",
                        "Pere Example (alumno) ha escrito o cambiado la nota para los instructores sobre Nit.", APP));
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
                // N-32a to every member the activity admits (no levels: all 25 ACTIVE members of the club, the admin's and the
                // instructor's member records included), e-mailed as the publication asked; N-32b once, to Pere, the registrant
                // (the club's cancellation sends N-32c, not a second N-32b); N-32d and N-32c to Pere only.
                var expected = new ArrayList<>(List.of(
                        notice("N-32a", "MEMBER", "s08-laura", "ca", "Activitat publicada", "Seminari de salts · ds 17", APP, EMAIL),
                        notice("N-32a", "MEMBER", "s08-joan", "es", "Actividad publicada", "Seminario de saltos · sáb 17", APP, EMAIL),
                        notice("N-32a", "MEMBER", "s08-pere", "en", "Activity published", "Jumping seminar · 17 Sat", APP, EMAIL),
                        notice("N-32a", "MEMBER", "s08-admin", "ca", "Activitat publicada", "Seminari de salts · ds 17", APP, EMAIL),
                        notice("N-32a", "MEMBER", "s08-inst", "es", "Actividad publicada", "Seminario de saltos · sáb 17", APP, EMAIL),
                        notice("N-32b", "MEMBER", "s08-pere", "en", "Activity registration", "Registration confirmed for Jumping seminar · 17 Sat", APP),
                        notice("N-32d", "MEMBER", "s08-pere", "en", "Activity changed by the club", "Jumping seminar: Start time: 10:30", APP, EMAIL, SMS),
                        notice("N-32c", "MEMBER", "s08-pere", "en", "Activity cancelled by the club", "Jumping seminar: Suspès per la pluja.", APP, EMAIL, SMS)));
                IntStream.range(0, 20).forEach(i -> expected.add(notice("N-32a", "MEMBER", "s08-c" + i, "ca", "Activitat publicada", "Seminari de salts · ds 17", APP, EMAIL)));
                expect(expected.toArray(String[]::new));
            }
            case REMINDER -> {
                mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-pere")), new Update().set("notificationPreferences", new Document("reminderMinutesBefore", 60)), "members");
                prepare(() -> book("s08-pere", "s08-m-pere", "Pere", "thu", "s08-d-nit"));
                clock.setInstant(local("2026-10-08T17:50"));
                act(() -> runner.scheduled(CLUB, true, reminders, clock.instant()).orElseThrow());
                expect(notice("N-13", "MEMBER", "s08-pere", "en", "Class reminder", "Today at 18:50 · Classe thu · Central · with Nit.", APP, "EMAIL:SKIPPED_BY_PREFERENCE", PUSH));
            }
        }
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

    /** One notice: «code audience account locale «title» «body» [channel:status…]», the deliveries sorted. */
    static String notice(String code, String audience, String accountId, String locale, String title, String body, String... deliveries) {
        return code + " " + audience + " " + accountId + " " + locale + " «" + title + "» «" + body + "» " + Stream.of(deliveries).sorted().toList();
    }
    /** N-16's staff copy to the admins (notif.N-16.staff.body, `auto_cancel`): one per class at risk, reviewed at 7:30 on its own day. */
    static String atRisk(String classLine, String reviewDay) {
        return notice("N-16", "ADMINS", "s08-admin", "ca", "Possible anul·lació de classe", "Example Agility Club: " + classLine
                + " encara no té prou alumnes. Si ningú més s'hi apunta, s'anul·larà " + reviewDay + " a les 7:30.", APP);
    }
    /** Every notice the case's action left in the club, of any code, in {@link #notice} form. */
    List<String> notices() {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class).stream().map(n -> notice(n.code(), String.valueOf(n.audience()),
                n.recipient() == null ? "-" : n.recipient().accountId(), n.locale(), n.title(), n.body(),
                n.deliveries().stream().map(d -> d.channel() + ":" + d.status()).toArray(String[]::new))).toList();
    }
    /** The complete set: every expected notice once, nothing missing, nothing else (no extra recipient, code or duplicate). */
    void expect(String... notices) { assertThat(notices()).containsExactlyInAnyOrder(notices); }
    Notification only(String code) {
        var found = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)), Notification.class);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }
}
