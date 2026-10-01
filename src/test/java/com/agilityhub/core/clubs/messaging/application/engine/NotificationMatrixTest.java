package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.InMemoryMessagingPorts;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.SignupContactPort;
import com.agilityhub.core.clubs.messaging.domain.ChannelResolver;
import com.agilityhub.core.clubs.messaging.domain.ChannelTruthTable;
import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscriptionRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationChannel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E7-T04 step 3, the channel × audience × preference × module matrix of the gate (T-11-02 completed; CATALEG_NOTIFICACIONS
 * «Regles» 8: «per a cada codi, la matriu canals×públics amb preferències ON/OFF i mòduls ON/OFF»), through the real
 * {@link NotificationEngine#process} rather than the resolver alone: for <b>every</b> catalog row of stage R1 × every audience of
 * the row × every channel of its caps and its push × the member's preference ON/OFF (e-mail of the code's category and
 * `pushClubNews`) × the modules (all ON; `SMS`+`PUSH` OFF; and, for a code with module guards, its own modules OFF) × the
 * contact (present: an account, two e-mails, two phones, two push devices; absent: none). The template enables exactly the
 * channel of the case (PUSH is the code's, never the template's).
 *
 * <p>The expected answer is the R-11-03 truth table as {@link ChannelTruthTable#expected} writes it, row by row (not from the
 * engine's code), plus what the engine adds around the resolver: a code whose module is off is never emitted (R-11-17), a
 * `SYSTEM` code is never the engine's (its product e-mail is `SystemNotificationService`'s, T-11-25), an applicant without an
 * address is nobody, and N-02's e-mail copy belongs to S01's welcome (its owner excludes it, E76). The case asserts the exact
 * delivery list with its `SKIPPED_*` reasons, the code, the audience and the recipient, and that the texts were rendered.</p>
 */
class NotificationMatrixTest {
    static final String CLUB = "e7t04-matrix", MEMBER_ID = "member-matrix", INSTRUCTOR_ID = "instructor-matrix", STAFF_ID = "member-staff";
    static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    /** The catalog codes the engine can never produce from an event: no event at all (the S14 worker triggers N-50, E11-T01) or a request (N-53, S17). */
    static final List<String> WITHOUT_EVENT = List.of("N-50", "N-53");
    static final Map<String, AtomicInteger> COUNTS = new TreeMap<>();
    static final ClubConfig.ClubView VIEW = new ClubConfig.ClubView(CLUB, CLUB, "Club Matriu", List.of("ca", "es", "en"), "ca", "Europe/Madrid", "EUR", null, null,
            "ACTIVE", null);
    static final Map<String, Object> PARAMETERS = parameters();
    static final IcuMessageSource MESSAGES = messages();
    static final MessageTemplateSeed SEEDS = MessageTemplateSeed.load();

    enum Modules { ON, CHANNELS_OFF, CODE_OFF }
    enum ContactKind { PRESENT, ABSENT }

    record Case(NotificationSpec spec, NotificationAudience audience, NotificationChannel channel, boolean preference, Modules modules, ContactKind contact) {
        @Override public String toString() { return spec.code() + " " + audience + " " + channel + " pref=" + preference + " modules=" + modules + " contact=" + contact; }
    }

    /** Every R1 row × audience × channel of its caps and push × preference × modules × contact. */
    static Stream<Case> cases() {
        var cases = new ArrayList<Case>();
        for (var spec : NotificationCatalog.specs()) {
            if (spec.stage() != NotificationSpec.Stage.R1 || WITHOUT_EVENT.contains(spec.code())) { continue; }
            for (var audience : spec.audiences()) {
                var channels = new LinkedHashSet<NotificationChannel>(spec.caps(audience));
                if (spec.push().contains(audience)) { channels.add(PUSH); }
                for (var channel : channels) {
                    for (boolean preference : new boolean[] {true, false}) {
                        for (var modules : Modules.values()) {
                            if (modules == Modules.CODE_OFF && spec.moduleGuards().isEmpty()) { continue; }
                            for (var contact : ContactKind.values()) { cases.add(new Case(spec, audience, channel, preference, modules, contact)); }
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("cases")
    void T_11_02_everyR1CodeTimesAudienceChannelPreferenceModuleAndContactThroughTheEngine(Case c) throws Exception {
        COUNTS.computeIfAbsent(c.spec().code(), code -> new AtomicInteger()).incrementAndGet();
        var stored = run(c);
        var expected = expected(c);
        if (expected == null) { assertThat(stored).as(c + ": no notification").isEmpty(); return; }
        assertThat(stored).as(c + ": one notification").hasSize(1);
        var notification = stored.getFirst();
        assertThat(notification.code()).isEqualTo(c.spec().code());
        assertThat(notification.audience()).isEqualTo(c.audience());
        assertThat(notification.category()).isEqualTo(c.spec().category());
        assertThat(notification.recipient().accountId()).isEqualTo(c.contact() == ContactKind.PRESENT ? ChannelTruthTable.ACCOUNT : null);
        assertThat(notification.title()).as(c + " title").isNotBlank();
        assertThat(notification.deliveries()).extracting(d -> new ChannelResolver.Planned(d.channel(), d.target(), d.status()))
                .as(c.toString()).containsExactlyInAnyOrderElementsOf(expected);
        // A queued delivery is due at once; the feed copy is delivered on creation (S11 §5).
        assertThat(notification.deliveries()).allSatisfy(d -> {
            if (d.status() == DeliveryStatus.QUEUED) { assertThat(d.nextAttemptAt()).isEqualTo(NOW); }
            if (d.status() == DeliveryStatus.DELIVERED) { assertThat(d.channel()).isEqualTo(APP); }
        });
    }

    /** The catalog rows that no event produces are listed, never silently skipped. */
    @Test void T_11_02_theRowsWithoutAnEventAreNamed() {
        var noEvent = NotificationCatalog.specs().stream().filter(spec -> spec.stage() == NotificationSpec.Stage.R1 && spec.eventTypes().isEmpty()).map(NotificationSpec::code).toList();
        assertThat(noEvent).containsExactlyElementsOf(WITHOUT_EVENT);
    }

    @AfterAll static void report() {
        int total = COUNTS.values().stream().mapToInt(AtomicInteger::get).sum();
        System.out.println("E7-T04 engine matrix: " + total + " cases over " + COUNTS.size() + " R1 codes; per code " + COUNTS + "; without event: " + WITHOUT_EVENT);
    }

    // ---- the expected answer

    /** `null` = no notification at all; otherwise the R-11-03 deliveries of the case. */
    static List<ChannelResolver.Planned> expected(Case c) {
        var spec = c.spec();
        if (!spec.templated()) { return null; }                                                       // SYSTEM: SystemNotificationService's
        if (!modules(c).containsAll(spec.moduleGuards())) { return null; }                             // R-11-17: no event, no notice
        var account = c.contact() == ContactKind.PRESENT;
        if (c.audience() == APPLICANT) {
            if (!account) { return null; }                                                            // no address: nobody to write to
            return List.of(new ChannelResolver.Planned(EMAIL, "applicant@example.test", DeliveryStatus.QUEUED),
                    new ChannelResolver.Planned(APP, ChannelTruthTable.ACCOUNT, DeliveryStatus.DELIVERED));
        }
        var modules = modules(c);
        var planned = ChannelTruthTable.expected(spec, c.audience(), row(c), new ChannelResolver.Modules(modules.contains(Module.SMS), modules.contains(Module.PUSH)),
                null, c.preference(), c.preference(), account ? ChannelTruthTable.ContactKind.PRESENT : ChannelTruthTable.ContactKind.ABSENT);
        // N-02: S01's welcome e-mail carries the link; the census owner leaves the e-mail copy out (E76).
        if (spec.code().equals("N-02")) { planned = planned.stream().filter(p -> p.channel() != EMAIL).toList(); }
        return planned;
    }
    /** The template enables exactly the channel of the case (APP, EMAIL or SMS); a PUSH case has every template cell off. */
    static Map<NotificationChannel, Boolean> row(Case c) {
        var row = new EnumMap<NotificationChannel, Boolean>(NotificationChannel.class);
        for (var channel : List.of(APP, EMAIL, SMS)) { row.put(channel, channel == c.channel()); }
        return row;
    }
    static Set<Module> modules(Case c) {
        var on = EnumSet.allOf(Module.class);
        switch (c.modules()) {
            case ON -> { }
            case CHANNELS_OFF -> { on.remove(Module.SMS); on.remove(Module.PUSH); }
            case CODE_OFF -> on.removeAll(c.spec().moduleGuards());
        }
        return on;
    }

    // ---- the engine over doubles

    List<Notification> run(Case c) throws Exception {
        var spec = c.spec();
        var config = new ClubConfig(VIEW, PARAMETERS, modules(c), null, Map.of());
        var configs = mock(ClubConfigService.class); when(configs.get(CLUB)).thenReturn(config);
        var templates = mock(TemplateProvider.class);
        if (spec.templated()) { when(templates.forCode(any(), any(), any())).thenReturn(template(spec, c)); }
        var ports = new InMemoryMessagingPorts();
        boolean present = c.contact() == ContactKind.PRESENT;
        var preferences = new LinkedHashMap<String, Object>();
        if (spec.category().templated()) { preferences.put("emailByCategory", Map.of(spec.category().name(), c.preference())); }
        preferences.put("pushClubNews", c.preference());
        var emails = present ? ChannelTruthTable.EMAILS.stream().map(e -> new MemberContact.Email(e, false)).toList() : List.<MemberContact.Email>of();
        var phones = present ? ChannelTruthTable.PHONES : List.<String>of();
        String account = present ? ChannelTruthTable.ACCOUNT : null;
        ports.put(new MemberContact(MEMBER_ID, account, "Laura Serra Puig", "Laura", "FEMALE", "ca", emails, phones, preferences, "ACTIVE",
                List.of(new MemberContact.DogContact("dog-matrix", "Duna", "FEMALE", "ACTIVE"))));
        ports.put(new MemberContact(STAFF_ID, account, "Marta Instructora", "Marta", "FEMALE", "ca", emails, phones, preferences, "ACTIVE", List.of()));
        ports.instructorMembers.put(INSTRUCTOR_ID, STAFF_ID); ports.activeInstructors.add(INSTRUCTOR_ID); ports.adminIds.add(STAFF_ID);
        var notifications = mock(NotificationRepository.class);
        when(notifications.byDedupKeys(any())).thenReturn(Map.of());
        var stored = new ArrayList<Notification>();
        doAnswer(invocation -> { stored.addAll(invocation.getArgument(0)); return null; }).when(notifications).insertAll(anyList());
        var subscriptions = mock(PushSubscriptionRepository.class);
        when(subscriptions.activeFor(any())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            return ids.contains(ChannelTruthTable.ACCOUNT) ? Map.of(ChannelTruthTable.ACCOUNT, ChannelTruthTable.SUBSCRIPTIONS) : Map.of();
        });
        var accounts = mock(NotificationAccounts.class);
        when(accounts.find(any())).thenAnswer(invocation -> ChannelTruthTable.ACCOUNT.equals(invocation.getArgument(0))
                ? Optional.of(new NotificationAccounts.Recipient(ChannelTruthTable.ACCOUNT, "laura@example.test", "ca", null)) : Optional.empty());
        var engine = new NotificationEngine(configs, MESSAGES, templates, new RecipientResolver(ports, ports, ports), notifications, subscriptions, accounts,
                List.of(new Owner(spec.code(), c.audience(), present)), (bookingId, trainingBookingId, now) -> true, mock(EventPublisher.class),
                mock(NotificationDispatcher.class), Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));
        // One event per case: the code's first catalog event (a second one of the same code is the same row of the table).
        try (var tenant = TenantContext.open(CLUB)) { engine.process(trigger(spec, spec.eventTypes().getFirst())); }
        return stored;
    }

    /** The club template of the code: the product seed with only the case's channel on for its audience. */
    static MessageTemplate template(NotificationSpec spec, Case c) {
        var seeded = SEEDS.of(spec.code()).orElseThrow();
        var matrix = new EnumMap<NotificationAudience, Map<NotificationChannel, Boolean>>(NotificationAudience.class);
        for (var audience : List.of(MEMBER, INSTRUCTORS, ADMINS)) { matrix.put(audience, audience == c.audience() ? row(c) : Map.of(APP, false, EMAIL, false, SMS, false)); }
        var locales = List.of("ca", "es", "en");
        return new MessageTemplate("template-" + spec.code(), CLUB, spec.code(), TemplateKind.CATALOG, spec.category(), seeded.title(locales, "ca"), seeded.body(locales, "ca"),
                seeded.smsBody(locales, "ca"), seeded.icon(), seeded.color(), matrix, true, spec.mandatory(), false, TemplateStatus.ACTIVE, 0L, NOW, "seed", NOW, "seed");
    }

    /** The event of the code with the payload keys S11 §7's conditions and the code's `dedupKey` read. */
    static NotificationTrigger trigger(NotificationSpec spec, String type) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("bookingId", "booking-matrix"); payload.put("batchId", "batch-matrix"); payload.put("status", "ACTIVE");
        payload.put("provider", spec.code().equals("N-35") ? "STRIPE" : "SEPA_XML");
        return new NotificationTrigger("event-" + spec.code() + "-" + type, type, CLUB, "Aggregate", "aggregate-matrix", NOW, payload, null, null, DomainEvent.Origin.SYSTEM);
    }

    /**
     * The owner of every event: the case's code only (an event of several codes — `BookingCreated` → N-04, N-36, N-46 — answers
     * one), for the case's audience only, with its recipient; N-02's census owner leaves the e-mail copy out (S01's welcome).
     */
    record Owner(String code, NotificationAudience audience, boolean present) implements NotificationFactsPort {
        @Override public Set<String> eventTypes() {
            var types = new HashSet<String>(); NotificationCatalog.specs().forEach(spec -> types.addAll(spec.eventTypes())); return types;
        }
        @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
            if (!code.equals(this.code)) { return Optional.empty(); }
            var builder = NotificationFacts.builder().audiences(audience.name()).noMembers();
            switch (audience) {
                case MEMBER -> builder.member(MEMBER_ID, null);
                case INSTRUCTORS -> builder.instructors(null, List.of(INSTRUCTOR_ID));
                case ADMINS -> { }
                case APPLICANT -> builder.applicant(new SignupContactPort.ApplicantContact(present ? "applicant@example.test" : null, "ca", "Laura Serra",
                        present ? ChannelTruthTable.ACCOUNT : null));
            }
            if (code.equals("N-02")) { builder.exclude("EMAIL"); }
            return Optional.of(builder.build());
        }
    }

    static Map<String, Object> parameters() {
        var values = new LinkedHashMap<String, Object>();
        new ParameterCatalog(new ObjectMapper()).entries().forEach((key, definition) -> { if (definition.defaultValue() != null) { values.put(key, definition.defaultValue()); } });
        values.put("messaging.notifyWeekOpening", true); values.put("messaging.notifyNewRingSetup", true);
        return values;
    }
    static IcuMessageSource messages() {
        try { return new IcuMessageSource(); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
