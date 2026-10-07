package com.agilityhub.core.clubs.messaging.application.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agilityhub.core.clubs.activities.domain.ActivityState;
import com.agilityhub.core.clubs.activities.domain.ActivityType;
import com.agilityhub.core.clubs.activities.domain.RegistrationOrigin;
import com.agilityhub.core.clubs.activities.domain.RegistrationState;
import com.agilityhub.core.clubs.activities.persistence.Activity;
import com.agilityhub.core.clubs.activities.persistence.ActivityRegistration;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationEventEnvelope;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.scheduling.domain.WeekState;
import com.agilityhub.core.clubs.scheduling.persistence.Week;
import com.agilityhub.core.clubs.training.domain.TrainingBookingState;
import com.agilityhub.core.clubs.training.domain.TrainingCancelReason;
import com.agilityhub.core.clubs.training.domain.TrainingCancelledBy;
import com.agilityhub.core.clubs.training.domain.TrainingOrigin;
import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T06 step 4 (R-11-12: «editing, preview and delivery read the same list»), the variable parity over every templated
 * code: for each R1 code with a template and each audience that reads it (MEMBER, APPLICANT), a real event goes through the
 * real engine — the owners' `NotificationFactsPort` beans over real documents of their contexts (census, scheduling,
 * bookings, training, activities, follow-up, weeks), the census directory, the club's formats — with a club template that
 * prints every variable the template may use ({@link NotificationCatalog#templateVariables}). The test fails when one renders
 * empty. A variable a variant of the event never has is named with its reason ({@link Case#absent}); the codes whose events
 * have no owner in this repository yet are named with their stage ({@link #LATER}), and the test fails as soon as one of
 * them gains an owner, so that stage adds its case here. The staff audiences read the product's staff copy
 * (`notif.N-xx.staff.*`), never the template: for them the test asserts that the copy renders without a missing value.
 * Step 2: the applicants' surnames reach N-01 and N-03.
 */
class TemplateVariableParityIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t06-parity", HOST = "parity.example.test";
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = local("2026-10-07T10:00"); // Wednesday 7 October 2026, 10:00 in Madrid
    static final String LAURA = "par-m-laura", NURIA = "par-m-nuria", MARTA = "par-m-marta", ADMIN = "par-m-admin";
    static final String DUNA = "par-d-duna", ROCK = "par-d-rock", KIRA = "par-d-kira";
    static final String CLASS = "par-class", CLASS2 = "par-class-2", PAST = "par-class-past", BOOKING = "par-b-1", PAST_BOOKING = "par-b-0";
    static final String FIFO_ENTRY = "par-w-fifo", TAKEN_ENTRY = "par-w-taken", ATTENDANCE = "par-a-1", TRAINING = "par-t-1", TRAINING_CANCELLED = "par-t-2";
    static final String ACTIVITY = "par-activity", TASK = "par-task", WEEK = "par-week-42", PLAN = "par-plan", LEVEL_C = "par-lv-c", LEVEL_D = "par-lv-d";
    static final List<String> DATA = List.of("members", "dogs", "accounts", "memberships", "instructors", "levels", "rings", "plans", "parameters", "class_sessions",
            "bookings", "waitlist_entries", "attendances", "training_bookings", "activities", "activity_registrations", "tasks", "weeks", "message_templates",
            "notifications", "domain_events", "signup_notification_admissions", "seat_locks", "announcements", "invoices", "upfront_payments");
    /**
     * The templated codes whose events no owner of this repository explains yet: their facts are the event's payload until
     * their stage writes the owner (and adds the code's case to this test).
     */
    static final Map<String, String> LATER = new TreeMap<>(Map.of(
            "N-31", "RingSetupChanged: course setups, S16 (later stage)",
            "N-50", "no event: the S14 export worker triggers it directly (not produced yet)"));

    @Autowired MongoTemplate mongo; @Autowired ObjectMapper mapper; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired NotificationEngine engine; @Autowired PlatformTransactionManager transactions; @Autowired List<NotificationFactsPort> owners; @Autowired EmailSender email;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    static Instant local(String dateTime) { return LocalDateTime.parse(dateTime).atZone(MADRID).toInstant(); }

    /**
     * One event of one code: `absent` names a variable this variant of the event never has, with the reason (it is still
     * offered by D9, because another variant has it).
     */
    record Case(String code, String type, String aggregateId, Map<String, Object> payload, Map<String, String> absent) {
        Case(String code, String type, Map<String, Object> payload) { this(code, type, "par-aggregate", payload, Map.of()); }
        String label() { return code + " " + type; }
    }

    @BeforeEach void parityClub() {
        clock.setInstant(NOW);
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), collection); }
        mongo.remove(Query.query(Criteria.where("_id").regex("^par-")), "accounts");
        mongo.remove(Query.query(Criteria.where("_id").is(CLUB)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(CLUB, HOST));
        tree.set("modules", mapper.valueToTree(List.of(Module.values()))); tree.set("locales", mapper.valueToTree(List.of("ca", "es"))); tree.put("defaultLocale", "ca");
        tree.put("name", "Club Agility Paritat");
        tree.set("paymentProviders", mapper.valueToTree(Map.of("MANUAL", Map.of("enabled", true,
                "instructions", Map.of("values", Map.of("ca", "Paga a la recepció del club.", "es", "Paga en la recepción del club."), "defaultLocale", "ca")))));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
        parameter("messaging.notifyWeekOpening", true); parameter("jobs.alertAdminsOnFailure", true); parameter("levels.enabled", true);
        parameter("billing.stripeReceiptEmail", false);
        // Census: Laura (two dogs), Núria (an applicant), Marta (an instructor), an administrator.
        account("par-laura", "MEMBER", LAURA); member(LAURA, "par-laura", "Laura", "Serra", "Puig", "FEMALE", "ACTIVE");
        dog(DUNA, LAURA, "Duna", "FEMALE", LEVEL_C, "ACTIVE"); dog(ROCK, LAURA, "Rock", "MALE", LEVEL_C, "ACTIVE");
        member(NURIA, null, "Núria", "Vidal", "Mas", "FEMALE", "PENDING"); dog(KIRA, NURIA, "Kira", "FEMALE", null, "PENDING");
        account("par-marta", "INSTRUCTOR", MARTA); member(MARTA, "par-marta", "Marta", "Roca", null, "FEMALE", "ACTIVE");
        account("par-admin", "ADMIN", ADMIN); member(ADMIN, "par-admin", "Aleix", "Font", null, "MALE", "ACTIVE");
        mongo.save(new Document("_id", "par-instructor").append("clubId", CLUB).append("memberId", MARTA).append("shortName", "Marta").append("color", "#123456")
                .append("active", true).append("version", 0), "instructors");
        for (String level : List.of("C", "D")) {
            mongo.save(new Document("_id", "par-lv-" + level.toLowerCase()).append("clubId", CLUB).append("code", level).append("nameKeys", List.of(level.toLowerCase()))
                    .append("name", new Document("values", new Document("ca", "Nivell " + level).append("es", "Nivel " + level)).append("defaultLocale", "ca"))
                    .append("active", true).append("order", level.equals("C") ? 1 : 2).append("capacity", 6).append("grantsFreeTraining", true).append("version", 0), "levels");
        }
        mongo.save(new Document("_id", "par-ring").append("clubId", CLUB).append("name", "Central").append("shortName", "CEN").append("color", "#8FCE8F")
                .append("allowsFreeTraining", true).append("active", true).append("order", 1).append("version", 0), "rings");
        mongo.save(new Document("_id", PLAN).append("clubId", CLUB).append("code", "MENSUAL").append("name",
                new Document("values", new Document("ca", "Quota mensual").append("es", "Cuota mensual")).append("defaultLocale", "ca")).append("version", 0), "plans");
        // Scheduling: Thursday's class (Laura's Duna booked, Rock offered a FIFO seat), another class with a taken ALL_AT_ONCE offer, Tuesday's past class.
        session(CLASS, "2026-10-08T18:50", "B+C"); session(CLASS2, "2026-10-09T19:30", "C"); session(PAST, "2026-10-06T18:50", "B+C");
        mongo.insert(booking(BOOKING, CLASS, DUNA, "2026-10-08T18:50")); mongo.insert(booking(PAST_BOOKING, PAST, DUNA, "2026-10-06T18:50"));
        mongo.insert(new WaitlistEntry(FIFO_ENTRY, CLUB, CLASS, ROCK, LAURA, "par-laura", NOW.minusSeconds(86_400), WaitlistState.NOTIFIED, 1, NOW, NOW.plusSeconds(7_200), null,
                null, null, null, local("2026-10-08T18:50"), "2026-W41", null, NOW, "par-laura", NOW, "par-laura"));
        mongo.insert(new WaitlistEntry(TAKEN_ENTRY, CLUB, CLASS2, ROCK, LAURA, "par-laura", NOW.minusSeconds(86_400), WaitlistState.ACTIVE, 1, NOW.minusSeconds(600), null,
                NOW.minusSeconds(600), null, null, null, local("2026-10-09T19:30"), "2026-W41", null, NOW, "par-laura", NOW, "par-laura"));
        mongo.insert(new Attendance(ATTENDANCE, CLUB, PAST_BOOKING, PAST, LocalDate.parse("2026-10-06"), local("2026-10-06T18:50"), local("2026-10-06T19:50"), DUNA, LAURA,
                AttendanceState.NO_SHOW, NOW.minusSeconds(50_000), null, null, new Attendance.NoShowNotice(NOW, "par-no-show-event", null), List.of(), null, NOW, NOW));
        // Training: Friday 8:00–8:30 on Central; one cancelled by the club with a note.
        mongo.insert(training(TRAINING, TrainingBookingState.ACTIVE, null)); mongo.insert(training(TRAINING_CANCELLED, TrainingBookingState.CANCELLED_BY_CLUB, "El ring és en obres."));
        // Activities: a published seminar on Saturday 17 with Laura registered.
        mongo.insert(activity()); mongo.insert(new ActivityRegistration("par-registration", CLUB, ACTIVITY, LAURA, RegistrationState.ACTIVE, RegistrationOrigin.APP,
                NOW.minusSeconds(86_400), new ActivityRegistration.RegisteredBy("par-laura", null, "Laura Serra"), null, null, null, null, null, local("2026-10-17T10:00"), null,
                null, NOW, "par-laura", NOW, "par-laura"));
        // Follow-up: Marta's task for Duna. Weeks: the week of Monday 12, validated.
        mongo.insert(new Task(TASK, CLUB, DUNA, LAURA, "Treballar el contacte a la zona de salts", TaskState.PENDING, new Task.Actor("par-marta", AuthorRole.INSTRUCTOR,
                "Marta Roca"), null, null, null, NOW, NOW, null, null, 0, null));
        mongo.insert(new Week(WEEK, CLUB, 2026, 42, LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), WeekState.VALIDATED, NOW, "par-admin", null, null, NOW,
                "par-admin", null, NOW, "par-admin", NOW, "par-admin", NOW, null));
        // Billing (E8-T02): Laura's September receipt, which a card failure (N-35, E8-T04's path) is about.
        var fee = new com.agilityhub.core.shared.domain.Money(6000, "EUR"); var zero = new com.agilityhub.core.shared.domain.Money(0, "EUR");
        mongo.insert(new com.agilityhub.core.payments.persistence.Invoice("par-invoice", CLUB, "2026", 912, "2026-0912", "2026-08-25", "2026-09", LAURA,
                new com.agilityhub.core.payments.persistence.Invoice.MemberSnapshot(1, "Laura Serra Puig", null), List.of(new com.agilityhub.core.payments.persistence.Invoice.Line(1,
                com.agilityhub.core.payments.domain.InvoiceLineOrigin.MONTHLY_FEE, null, null, "Quota mensual — Setembre 2026", fee, java.math.BigDecimal.ZERO, zero, fee)),
                fee, zero, fee, new com.agilityhub.core.payments.persistence.Invoice.PaymentMethodSnapshot(com.agilityhub.core.payments.domain.PaymentMethodType.CARD,
                "···· 4242", "Laura Serra", null, "4242", null), com.agilityhub.core.payments.domain.InvoiceStatus.FAILED,
                com.agilityhub.core.payments.domain.InvoiceKind.PERIODIC, "par-run", null, false, null, null, NOW, "La targeta ha estat rebutjada", null, null, zero, null, 1L,
                NOW, "par-admin", NOW, "par-admin"));
        // E8-T04: both real N-30 variants; upfront payments have no invoice number.
        var paidInvoice = mongo.findById("par-invoice", Document.class, "invoices");
        paidInvoice.put("_id", "par-paid-invoice"); paidInvoice.put("number", 913L); paidInvoice.put("displayNumber", "2026-0913");
        paidInvoice.put("status", "PAID"); paidInvoice.put("paidAt", java.util.Date.from(NOW));
        paidInvoice.put("failedAt", null); paidInvoice.put("failureReason", null);
        mongo.insert(paidInvoice, "invoices");
        mongo.insert(new com.agilityhub.core.payments.persistence.UpfrontPayment("par-upfront", CLUB, LAURA, DUNA, "ENTRY_FEE", "ENTRY_FEE",
                fee, fee, "PAID", "STRIPE", "par-checkout", NOW, NOW, null, "par-submission", null, null, null,
                new com.agilityhub.core.payments.persistence.UpfrontPayment.StripeRefs("pi_parity", null), null, null, List.of(), null));
        ((FakeEmailSender) email).clear();
        logs.list.clear(); logs.start(); ((Logger) LoggerFactory.getLogger(TemplateRenderer.class)).addAppender(logs);
    }
    @AfterEach void release() { ((Logger) LoggerFactory.getLogger(TemplateRenderer.class)).detachAppender(logs); }

    void parameter(String key, Object value) {
        mongo.insert(new Parameter(UUID.randomUUID().toString(), CLUB, key, value, "unknown", "club", null, List.of(), 0L, NOW)); configs.invalidate(CLUB);
    }
    void account(String id, String role, String memberId) {
        mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + id, "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, NOW));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership(id, id, CLUB, memberId, Set.of(com.agilityhub.core.identity.domain.Role.valueOf(role)),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.valueOf(role)));
    }
    void member(String id, String account, String firstName, String lastName1, String lastName2, String gender, String status) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("accountId", account).append("firstName", firstName).append("lastName1", lastName1)
                .append("lastName2", lastName2).append("gender", gender).append("memberNumber", Math.abs(id.hashCode() % 10000)).append("status", status)
                .append("bookingBlock", new Document("active", false)).append("signup", new Document("locale", "ca").append("planIdRequested", PLAN))
                .append("contactEmails", List.of(new Document("email", id + "@example.test"))).append("phones", List.of(new Document("prefix", "+34").append("number", "600000101")))
                .append("version", 0), "members");
    }
    void dog(String id, String memberId, String name, String sex, String level, String status) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("memberId", memberId).append("name", name).append("sex", sex).append("status", status)
                .append("levelId", level).append("version", 0), "dogs");
    }
    void session(String id, String start, String description) {
        var starts = local(start); var s = new LinkedHashMap<String, Object>();
        s.put("id", id); s.put("clubId", CLUB); s.put("weekId", WEEK); s.put("date", start.substring(0, 10)); s.put("startTime", start.substring(11));
        s.put("endTime", LocalDateTime.parse(start).plusHours(1).toLocalTime().toString()); s.put("startsAt", starts.toString()); s.put("endsAt", starts.plusSeconds(3600).toString());
        s.put("ringId", "par-ring"); s.put("state", "ACTIVE"); s.put("levelIds", List.of(LEVEL_C)); s.put("instructorIds", List.of("par-instructor")); s.put("capacity", 6);
        s.put("capacityMode", "MANUAL"); s.put("description", description); s.put("counters", Map.of("booked", 1, "waiting", 1));
        s.put("risk", Map.of("exempt", false, "notifiedBookingIds", List.of())); s.put("version", 0);
        mongo.insert(mapper.convertValue(s, com.agilityhub.core.clubs.scheduling.persistence.ClassSession.class));
    }
    static Booking booking(String id, String classId, String dogId, String start) {
        return new Booking(id, CLUB, classId, dogId, LAURA, BookingState.ACTIVE, BookingOrigin.APP, NOW.minusSeconds(86_400), new Booking.Actor("par-laura", null, "Laura Serra"),
                local(start), local(start).plusSeconds(3600), "2026-W41", null, null, null, null, null, null, null, null, null, null, null, null, null, null, NOW, "par-laura", NOW,
                "par-laura");
    }
    static TrainingBooking training(String id, TrainingBookingState state, String note) {
        boolean cancelled = state != TrainingBookingState.ACTIVE;
        return new TrainingBooking(id, CLUB, LAURA, DUNA, "par-ring", local("2026-10-09T08:00"), local("2026-10-09T08:30"), "par-slot", 0, local("2026-10-05T00:00"), state,
                TrainingOrigin.APP, "par-laura", null, cancelled ? NOW : null, cancelled ? TrainingCancelledBy.ADMIN : null, cancelled ? TrainingCancelReason.ADMIN_LATE : null,
                note, null, null, null, NOW, NOW, "par-laura");
    }
    static Activity activity() {
        return new Activity(ACTIVITY, CLUB, new LocalizedText(Map.of("ca", "Seminari de salts", "es", "Seminario de saltos"), "ca"), ActivityType.SEMINAR, null, null, null,
                null, List.of(), new Activity.Location(true, null, null, null), List.of("par-ring"), LocalDate.parse("2026-10-17"), "10:00", "13:00", local("2026-10-17T10:00"),
                local("2026-10-17T13:00"), null, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-16"), local("2026-10-01T00:00"), local("2026-10-16T23:59"), null, 12,
                List.of(), true, "MEMBERS", List.of(), "seminari-de-salts", ActivityState.PUBLISHED, NOW, "par-admin", null, null, new Activity.Counters(1, 0), null, List.of(),
                null, NOW, "par-admin", NOW, "par-admin");
    }

    /** Every case: the R1 templated codes with an owner, each through the event (or the variant) that carries all its variables. */
    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        var upfront = Map.<String, Object>of("amountMinor", 4500, "currency", "EUR");
        cases.add(new Case("N-01", "SignupSubmitted", "par-aggregate", Map.of("memberId", NURIA, "dogIds", List.of(KIRA), "planId", PLAN, "paymentMethodType", "MANUAL",
                "locale", "ca", "dogNames", List.of("Kira"), "upfrontTotal", upfront),
                Map.of("pay_link", "with MANUAL instructions the census gives an empty pay_link; the Stripe retry capability is delivery-only")));
        cases.add(new Case("N-02", "MemberValidated", Map.of("memberId", LAURA)));
        cases.add(new Case("N-03", "SignupRejected", Map.of("memberId", NURIA, "reason", "Documentació incompleta", "dogIds", List.of(KIRA), "memberWasActive", false,
                "locale", "ca")));
        var bookingOf = Map.<String, Object>of("bookingId", BOOKING, "classId", CLASS, "memberId", LAURA, "dogId", DUNA);
        cases.add(new Case("N-04", "BookingCreated", with(bookingOf, "origin", "APP")));
        cases.add(new Case("N-05", "BookingCancelled", with(bookingOf, "origin", "APP", "by", "MEMBER", "late", true)));
        cases.add(new Case("N-06", "TrainingBooked", Map.of("trainingBookingId", TRAINING, "memberId", LAURA, "dogId", DUNA, "origin", "APP")));
        cases.add(new Case("N-07", "TrainingCancelled", Map.of("trainingBookingId", TRAINING, "memberId", LAURA, "dogId", DUNA, "origin", "APP", "by", "MEMBER")));
        cases.add(new Case("N-08a", "ClassCancelledByClub", Map.of("classId", CLASS, "reason", "WEATHER", "adminText", "Plou massa: la classe queda anul·lada.",
                "affected", List.of(Map.of("memberId", LAURA, "dogId", DUNA, "bookingId", BOOKING)), "waitlistIds", List.of())));
        cases.add(new Case("N-08b", "ClassSessionUpdated", Map.of("classId", CLASS, "bookedCount", 1, "diff", Map.of("startTime", Map.of("before", "18:50", "after", "19:00")))));
        cases.add(new Case("N-09", "DogLevelChanged", Map.of("memberId", LAURA, "dogId", DUNA, "before", LEVEL_D, "after", LEVEL_C)));
        cases.add(new Case("N-13", "ReminderDue", "par-aggregate", Map.of("bookingId", BOOKING, "memberId", LAURA, "dogId", DUNA,
                "startsAt", local("2026-10-08T18:50").toString()), Map.of()));
        cases.add(new Case("N-13", "ReminderDue", "par-aggregate", Map.of("trainingBookingId", TRAINING, "memberId", LAURA, "dogId", DUNA,
                "startsAt", local("2026-10-09T08:00").toString()), Map.of("class_description", "a training booking has no class (kind = TRAINING; the seed prints the training line)")));
        cases.add(new Case("N-15", "WaitlistNotified", Map.of("classId", CLASS, "entryIds", List.of(FIFO_ENTRY))));
        cases.add(new Case("N-16", "ClassAtRisk", Map.of("classId", CLASS, "newBookingIds", List.of(BOOKING), "notifyAdmins", true, "dogsCount", 1,
                "reviewAt", local("2026-10-08T07:30").toString())));
        cases.add(new Case("N-19", "NoShowNoticeDue", Map.of("attendanceIds", List.of(ATTENDANCE))));
        cases.add(new Case("N-20", "TaskCreated", TASK, Map.of("memberId", LAURA, "dogId", DUNA, "textExcerpt", "Treballar el contacte a la zona de salts"), Map.of()));
        // A type of the club's `census.dogDocumentTypes` (the product default): its label in the recipient's language.
        cases.add(new Case("N-23", "DocumentReminderDue", Map.of("memberId", LAURA, "dogId", DUNA, "type", "VACCINATION_CARD")));
        cases.add(new Case("N-23", "DogDocumentPending", Map.of("memberId", LAURA, "dogId", DUNA, "type", "VACCINATION_CARD", "trigger", "MANUAL")));
        // E7-T04: «Enviar comunicat» to Laura (the batch is stored with the installed template, see `install`).
        cases.add(new Case("N-24", "AnnouncementSent", Map.of("batchId", "par-batch", "recipientCount", 1, "filters", List.of())));
        cases.add(new Case("N-29", "BookingBlockChanged", Map.of("memberId", LAURA, "reason", "Quota pendent", "active", true)));
        cases.add(new Case("N-30", "InvoicePaid", Map.of("invoiceId", "par-paid-invoice", "provider", "STRIPE", "paidAt", NOW.toString())));
        cases.add(new Case("N-30", "UpfrontPaymentSucceeded", "par-upfront", Map.of("paymentId", "par-upfront", "memberId", LAURA,
                "concept", "ENTRY_FEE", "provider", "STRIPE", "amountPaid", Map.of("amountMinor", 6000, "currency", "EUR")),
                Map.of("invoice_number", "an upfront payment has no issued invoice")));
        cases.add(new Case("N-32a", "ActivityPublished", Map.of("activityId", ACTIVITY, "notifyEmail", true)));
        cases.add(new Case("N-32b", "ActivityRegistrationChanged", Map.of("activityId", ACTIVITY, "memberId", LAURA, "state", "ACTIVE", "origin", "APP")));
        cases.add(new Case("N-32c", "ActivityCancelled", Map.of("activityId", ACTIVITY, "adminText", "Suspès per la pluja.", "affected", List.of(Map.of("memberId", LAURA)))));
        cases.add(new Case("N-32d", "ActivityUpdated", Map.of("activityId", ACTIVITY, "registrantCount", 1, "diff", Map.of("startTime", Map.of("before", "09:30", "after", "10:00")))));
        cases.add(new Case("N-33", "WeekOpened", Map.of("notified", true, "weekId", WEEK, "isoWeekStart", "2026-10-12")));
        // E8-T02: the billing owner explains InvoiceFailed (N-10 to the admins, N-35 to the member of a card failure).
        cases.add(new Case("N-35", "InvoiceFailed", Map.of("invoiceId", "par-invoice", "provider", "STRIPE", "reason", "card_declined")));
        cases.add(new Case("N-36", "BookingCreated", with(bookingOf, "origin", "BACKOFFICE")));
        cases.add(new Case("N-36", "BookingCancelled", with(bookingOf, "origin", "BACKOFFICE", "by", "ADMIN")));
        cases.add(new Case("N-37", "DogRegistered", Map.of("memberId", LAURA, "dogId", DUNA)));
        cases.add(new Case("N-37", "DogDeactivated", Map.of("memberId", LAURA, "dogId", DUNA, "reason", "CLUB")));
        cases.add(new Case("N-38", "MemberPaymentMethodChanged", Map.of("memberId", LAURA, "masked", "···· 2231")));
        cases.add(new Case("N-40", "BookingCancelled", with(bookingOf, "reason", "PAYMENT_TIMEOUT", "by", "SYSTEM", "origin", "SYSTEM")));
        cases.add(new Case("N-46", "WaitlistConsolidated", TAKEN_ENTRY, Map.of("classId", CLASS2, "entryId", TAKEN_ENTRY), Map.of()));
        cases.add(new Case("N-47", "TrainingCancelled", Map.of("trainingBookingId", TRAINING_CANCELLED, "memberId", LAURA, "dogId", DUNA, "origin", "BACKOFFICE", "by", "ADMIN")));
        cases.add(new Case("N-11a", "PackLowBalance", Map.of("memberId", LAURA, "dogId", DUNA, "remaining", 1, "expiresOn", "2026-11-11")));
        for (String event : List.of("PackExpiring", "PackExpired")) {
            cases.add(new Case("N-11b", event, Map.of("memberId", LAURA, "dogId", DUNA, "remaining", 1, "expiresOn", "2026-11-11")));
        }
        cases.add(new Case("N-18b", "InactivityResolved", Map.of("memberId", LAURA, "from", "2026-11", "to", "2026-12", "decision", "APPROVED",
                "fee", Map.of("firstMonth", Map.of("amountMinor", 2000, "currency", "EUR"), "followingMonths", Map.of("amountMinor", 1000, "currency", "EUR")),
                "admin_text", "Approved period", "cancelledBookings", List.of(Map.of("type", "CLASS", "id", BOOKING, "sessionDate", "2026-11-03")))));
        cases.add(new Case("N-18c", "InactivityEnded", Map.of("memberId", LAURA, "finishReason", "SCHEDULED")));
        for (String event : List.of("LeaveResolved", "LeaveCancelled")) {
            cases.add(new Case("N-28", event, Map.of("memberId", LAURA, "source", "MEMBER", "effectiveDate", "2026-10-31", "decision", "APPROVED",
                    "admin_text", "Decision note", "cancelledBookings", List.of(Map.of("type", "CLASS", "id", BOOKING, "sessionDate", "2026-11-03")))));
        }
        return cases;
    }
    static Map<String, Object> with(Map<String, Object> base, Object... pairs) {
        var map = new LinkedHashMap<>(base);
        for (int i = 0; i < pairs.length; i += 2) { map.put(pairs[i].toString(), pairs[i + 1]); }
        return map;
    }

    /** The club template of `code` printing every variable it may use: «Values: a=[[a]] | b=[[b]] |» in ca and es. */
    MessageTemplate install(String code, List<String> variables) {
        var spec = NotificationCatalog.byCode(code).orElseThrow();
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code)), MessageTemplate.class);
        String body = "Values: " + String.join(" ", variables.stream().map(v -> v + "=[[" + v + "]] |").toList());
        var seed = MessageTemplateSeed.load().of(code).orElseThrow();
        var sms = seed.smsBody().isEmpty() ? null : new LocalizedText(Map.of("ca", "[[club_name]]", "es", "[[club_name]]"), "ca");
        var template = mongo.insert(new MessageTemplate(null, CLUB, code, TemplateKind.CATALOG, spec.category(), new LocalizedText(Map.of("ca", "Paritat", "es", "Paridad"), "ca"),
                new LocalizedText(Map.of("ca", body, "es", body), "ca"), sms, spec.icon(), spec.color(), seed.matrix(), true, spec.mandatory(), true, TemplateStatus.ACTIVE, null,
                NOW, "parity", NOW, "parity"));
        if ("N-24".equals(code)) {
            // R-11-13: the batch the send stores, with this template and Laura as its only member.
            mongo.remove(Query.query(Criteria.where("_id").is("par-batch")), "announcements");
            mongo.insert(new com.agilityhub.core.clubs.messaging.persistence.Announcement("par-batch", CLUB, template.id(),
                    com.agilityhub.core.clubs.messaging.persistence.Announcement.SentTemplate.of(template), com.agilityhub.core.clubs.messaging.persistence.Announcement.MEMBERS,
                    List.of(), null, List.of(LAURA), 1, "par-admin", NOW));
        }
        return template;
    }
    /** The outbox delivery of one event, as `OutboxDispatcher` runs the engine. */
    String deliver(String type, String aggregateId, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        var envelope = new NotificationEventEnvelope(null, type, CLUB, "Aggregate", aggregateId, NOW, payload, "par-admin", null, DomainEvent.Origin.BACKOFFICE);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> engine.handle(eventId, envelope));
        return eventId;
    }
    List<Notification> notices(String code, String eventId) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(code).and("eventId").is(eventId)), Notification.class);
    }
    /** `variable=<value> |` of a rendered body: the value, or null when the variable is not in the body. */
    static String rendered(String body, String variable) {
        var matcher = Pattern.compile("(?:^|\\s)" + Pattern.quote(variable + "=") + "([^|]*)\\|").matcher(body);
        return matcher.find() ? matcher.group(1).strip() : null;
    }
    static boolean readsTheTemplate(NotificationAudience audience) { return audience == NotificationAudience.MEMBER || audience == NotificationAudience.APPLICANT; }

    @Test void R_11_12_everyVariableOfEveryTemplatedCodeRendersWithTheRealFactsOfEachAudience() {
        var covered = new TreeSet<String>(); var empty = new ArrayList<String>(); var staffMissing = new ArrayList<String>(); var report = new ArrayList<String>();
        for (var c : cases()) {
            var spec = NotificationCatalog.byCode(c.code()).orElseThrow();
            var variables = NotificationCatalog.templateVariables(spec);
            install(c.code(), variables);
            logs.list.clear();
            String eventId = deliver(c.type(), c.aggregateId(), c.payload());
            var notices = notices(c.code(), eventId);
            for (var audience : spec.audiences().stream().filter(TemplateVariableParityIT::readsTheTemplate).toList()) {
                var read = notices.stream().filter(n -> n.audience() == audience).toList();
                assertThat(read).as(c.label() + " " + audience + ": no notification").isNotEmpty();
                for (var notice : read) {
                    for (String variable : variables) {
                        String value = rendered(notice.body(), variable);
                        if (c.absent().containsKey(variable)) { continue; }
                        if (value == null || value.isEmpty()) { empty.add(c.label() + " " + audience + " [[" + variable + "]]"); }
                    }
                }
                covered.add(c.code() + "#" + audience);
                // The printed line never carries a credential: N-04's calendar link is signed.
                report.add(c.label() + " " + audience + ": " + read.getFirst().body().replaceAll("token=[^ |]+", "token=…[truncated]"));
            }
            // The staff copy (`notif.N-xx.staff.*`) of the same event renders without a missing value.
            logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(message -> message.contains("has no value for"))
                    .forEach(message -> staffMissing.add(c.label() + ": " + message));
        }
        report.forEach(line -> System.out.println("E7-T06 parity " + line));
        assertThat(empty).as("variables rendered empty with the real facts").isEmpty();
        assertThat(staffMissing).as("staff copy without a value").isEmpty();
        // Every templated R1 code × template audience is covered, or waits for its owner's stage.
        var expected = new TreeSet<String>(); var later = new TreeSet<String>();
        for (var spec : MessageTemplateSeed.eligible()) {
            for (var audience : spec.audiences().stream().filter(TemplateVariableParityIT::readsTheTemplate).toList()) {
                (LATER.containsKey(spec.code()) ? later : expected).add(spec.code() + "#" + audience);
            }
        }
        assertThat(covered).isEqualTo(expected);
        assertThat(later.stream().map(pair -> pair.substring(0, pair.indexOf('#'))).distinct()).containsExactlyInAnyOrderElementsOf(LATER.keySet());
        // A LATER code has no owner yet: the day one explains its event, this test must get its case.
        for (String code : LATER.keySet()) {
            var spec = NotificationCatalog.byCode(code).orElseThrow();
            assertThat(owners).as(code + " gained an owner: add its case").noneMatch(owner -> !Collections.disjoint(owner.eventTypes(), spec.eventTypes()));
        }
        System.out.println("E7-T06 parity: " + covered.size() + " code × audience pairs over " + cases().size() + " events; waiting for their owner: " + LATER);
    }

    /**
     * E7-T06 step 2 (codex #2, claude #2; R-11-12): N-01 and N-03 offer `[[member_last_names]]` (their rows have
     * `member_name`), and their audience is `APPLICANT`. A template «Cognoms: [[member_last_names]].» delivered through the
     * real engine and the census facts reads the applicant's surnames — `SignupSubmitted` to the applicant's address, and
     * `SignupRejected`. Before the fix the engine derived them for MEMBER recipients only: «Cognoms: .».
     */
    @Test void R_11_12_theApplicantsSurnamesReachN01AndN03() {
        var applicantCases = cases().stream().filter(c -> Set.of("N-01", "N-03").contains(c.code())).toList();
        assertThat(applicantCases).extracting(Case::type).containsExactly("SignupSubmitted", "SignupRejected");
        for (var c : applicantCases) {
            var applicant = applicantNotice(c, c.payload(), NURIA + "@example.test");
            assertThat(applicant.body()).as(c.code()).isEqualTo("Núria Vidal Mas. Cognoms: Vidal Mas.");
            assertThat(applicant.variables()).containsEntry("member_last_names", "Vidal Mas");
            assertThat(((FakeEmailSender) email).lastTo(NURIA + "@example.test").text()).as(c.code() + " e-mail").contains("Cognoms: Vidal Mas.");
            System.out.println("E7-T06 step 2 " + c.code() + " " + c.type() + " → " + applicant.body());
        }
        // Round 2 (nit #5): a readmission's applicant travels in the event. Its name parts are stripped, the first name too, so
        // stray spaces never empty the surnames; since E81 it carries `lastName2`, and an event written before reads `lastName1`.
        var readmitted = new LinkedHashMap<String, Object>(Map.of("email", "readmitted@example.test", "firstName", "  Núria ", "lastName1", "Vidal ", "lastName2", " Mas",
                "gender", "FEMALE", "locale", "ca"));
        var beforeE81 = new LinkedHashMap<String, Object>(readmitted); beforeE81.remove("lastName2");
        for (var c : applicantCases) {
            for (var variant : List.of(Map.entry(readmitted, "Núria Vidal Mas. Cognoms: Vidal Mas."), Map.entry(beforeE81, "Núria Vidal. Cognoms: Vidal."))) {
                var applicant = applicantNotice(c, with(c.payload(), "applicant", variant.getKey()), "readmitted@example.test");
                assertThat(applicant.body()).as(c.code() + " readmission " + variant.getKey().keySet()).isEqualTo(variant.getValue());
                assertThat(applicant.variables()).containsEntry("member_first_name", "Núria");
                System.out.println("E7-T06 round 2 " + c.code() + " " + c.type() + " readmission " + variant.getKey().keySet() + " → " + applicant.body());
            }
        }
    }
    /** The applicant's copy of one delivery of `c` with `payload`, through a template printing the name and the surnames. */
    Notification applicantNotice(Case c, Map<String, Object> payload, String address) {
        var spec = NotificationCatalog.byCode(c.code()).orElseThrow();
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB).and("code").is(c.code())), MessageTemplate.class);
        var seed = MessageTemplateSeed.load().of(c.code()).orElseThrow();
        mongo.insert(new MessageTemplate(null, CLUB, c.code(), TemplateKind.CATALOG, spec.category(), new LocalizedText(Map.of("ca", "Sol·licitud"), "ca"),
                new LocalizedText(Map.of("ca", "[[member_name]]. Cognoms: [[member_last_names]]."), "ca"), null, spec.icon(), spec.color(), seed.matrix(), true, false, true,
                TemplateStatus.ACTIVE, null, NOW, "parity", NOW, "parity"));
        ((FakeEmailSender) email).clear();
        String eventId = deliver(c.type(), c.aggregateId(), payload);
        var applicant = notices(c.code(), eventId).stream().filter(n -> n.audience() == NotificationAudience.APPLICANT).findFirst().orElseThrow();
        assertThat(applicant.recipient().email()).isEqualTo(address);
        return applicant;
    }
}
