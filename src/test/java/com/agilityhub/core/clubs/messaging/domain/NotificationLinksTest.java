package com.agilityhub.core.clubs.messaging.domain;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.messaging.domain.NotificationActionType.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T02 round 2, review #7 (R-11-11 «Correu: l'acció es tradueix en un enllaç profund a la mateixa ruta»): the route of every
 * native action per audience, with the action's own parameters, in the audience's app. E7-T05 step 5 (ruling E72): the routes
 * are the web's — a training booking opens `/entrenaments/{id}` (S09 §2), `OPEN_SETUP` opens no training screen, and N-42's
 * `OPEN_JOBS` opens the back office's processes at `/parametres#processos` (S15 §2).
 */
class NotificationLinksTest {
    private static final Map<String, String> ALL = Map.of("dogId", "dog-duna", "classSessionId", "class-a", "bookingId", "booking-a", "waitlistEntryId", "entry-a",
            "trainingBookingId", "training-a", "activityId", "activity-a", "taskId", "task-a", "invoiceId", "invoice-a", "memberId", "member-marc");

    static Stream<Arguments> routes() {
        return Stream.of(
                // MEMBER: the member app.
                Arguments.of(MEMBER, CHANGE_CLASS, ALL, "clubs", "/reservar?dogId=dog-duna"),
                Arguments.of(MEMBER, CHANGE_CLASS, Map.of(), "clubs", "/reservar"),
                Arguments.of(MEMBER, CLAIM_SEAT, ALL, "clubs", "/reservar/confirmar?waitlistEntryId=entry-a&classSessionId=class-a&dogId=dog-duna"),
                Arguments.of(MEMBER, CLAIM_SEAT, Map.of("dogId", "dog-duna"), null, null),
                Arguments.of(MEMBER, OPEN_BOOKING, ALL, "clubs", "/reserves/booking-a"),
                Arguments.of(MEMBER, OPEN_BOOKING, Map.of("trainingBookingId", "training-a", "dogId", "dog-duna"), "clubs", "/entrenaments/training-a"),
                Arguments.of(MEMBER, OPEN_BOOKING, Map.of("trainingBookingId", "t/1"), "clubs", "/entrenaments/t%2F1"),
                Arguments.of(MEMBER, OPEN_BOOKING, Map.of("classSessionId", "class-a", "dogId", "dog-duna"), "clubs", "/reservar?dogId=dog-duna"),
                Arguments.of(MEMBER, OPEN_DOG, ALL, "clubs", "/gossos?dogId=dog-duna"),
                Arguments.of(MEMBER, OPEN_TASKS, ALL, "clubs", "/gossos?dogId=dog-duna&taskId=task-a"),
                Arguments.of(MEMBER, OPEN_INVOICES, ALL, "clubs", "/rebuts?invoiceId=invoice-a"),
                Arguments.of(MEMBER, OPEN_ACTIVITY, ALL, "clubs", "/activitats/activity-a"),
                Arguments.of(MEMBER, OPEN_ACTIVITY, Map.of(), null, null),
                Arguments.of(MEMBER, OPEN_SETUP, ALL, null, null),
                Arguments.of(MEMBER, OPEN_EXPORT, ALL, "clubs", "/perfil"),
                Arguments.of(MEMBER, OPEN_CHALLENGE, ALL, null, null),
                Arguments.of(MEMBER, OPEN_MEMBER, ALL, null, null),
                Arguments.of(APPLICANT, OPEN_SIGNUP, ALL, null, null),
                // INSTRUCTORS: the instructor screens of the member app.
                Arguments.of(INSTRUCTORS, CHANGE_CLASS, ALL, "clubs", "/instructor/classes/class-a"),
                Arguments.of(INSTRUCTORS, OPEN_BOOKING, Map.of(), "clubs", "/instructor/avui"),
                Arguments.of(INSTRUCTORS, OPEN_DOG, ALL, "clubs", "/instructor/alumnes/dog-duna"),
                Arguments.of(INSTRUCTORS, OPEN_DOG, Map.of(), "clubs", "/instructor/avui"),
                Arguments.of(INSTRUCTORS, OPEN_TASKS, ALL, "clubs", "/instructor/alumnes/dog-duna/tasques"),
                Arguments.of(INSTRUCTORS, OPEN_TASKS, Map.of(), "clubs", "/instructor/avui"),
                Arguments.of(INSTRUCTORS, OPEN_JOBS, ALL, "clubs", "/instructor/avui"),
                Arguments.of(INSTRUCTORS, OPEN_CHALLENGE, ALL, null, null),
                // ADMINS: the back office.
                Arguments.of(ADMINS, OPEN_SIGNUP, ALL, "clubs-admin", "/preinscripcions/member-marc"),
                Arguments.of(ADMINS, OPEN_SIGNUP, Map.of(), "clubs-admin", "/tauler"),
                Arguments.of(ADMINS, OPEN_MEMBER, ALL, "clubs-admin", "/abonats/member-marc"),
                Arguments.of(ADMINS, OPEN_MEMBER, Map.of(), "clubs-admin", "/abonats"),
                Arguments.of(ADMINS, OPEN_INVOICES, ALL, "clubs-admin", "/facturacio"),
                Arguments.of(ADMINS, OPEN_DOG, ALL, "clubs-admin", "/gossos/dog-duna"),
                Arguments.of(ADMINS, OPEN_TASKS, Map.of(), "clubs-admin", "/gossos"),
                Arguments.of(ADMINS, CHANGE_CLASS, ALL, "clubs-admin", "/calendari"),
                Arguments.of(ADMINS, OPEN_ACTIVITY, ALL, "clubs-admin", "/activitats/activity-a"),
                Arguments.of(ADMINS, OPEN_ACTIVITY, Map.of(), "clubs-admin", "/activitats"),
                Arguments.of(ADMINS, OPEN_JOBS, ALL, "clubs-admin", "/parametres#processos"),
                Arguments.of(ADMINS, OPEN_JOBS, Map.of(), "clubs-admin", "/parametres#processos"),
                Arguments.of(ADMINS, OPEN_CHALLENGE, ALL, null, null));
    }

    @ParameterizedTest(name = "{0} {1} → {3} {4}") @MethodSource("routes")
    void R_11_11_everyNativeActionOpensItsRouteWithItsParameters(NotificationAudience audience, NotificationActionType type, Map<String, String> params, String app, String path) {
        var link = NotificationLinks.of(audience, type, params);
        if (app == null) { assertThat(link).isEmpty(); return; }
        assertThat(link).contains(new NotificationLinks.Link(app, path));
    }

    @Test void R_11_11_everyActionOfTheCatalogHasAMemberOrStaffRouteAndValuesAreEncoded() {
        // Every catalog code with an action (R1) opens a route for each audience that has it, except N-31's OPEN_SETUP: the web
        // opens no training screen for it, and its parameters name no ring for the course viewer (the e-mail has no button).
        var linked = new java.util.ArrayList<String>();
        for (var spec : NotificationCatalog.specs()) {
            if (spec.stage() != NotificationSpec.Stage.R1) { continue; }
            for (var audience : spec.audiences()) {
                var type = spec.action(audience);
                if (type == null) { continue; }
                var link = NotificationLinks.of(audience, type, ALL);
                if (type == OPEN_SETUP) { assertThat(link).as("%s %s %s", spec.code(), audience, type).isEmpty(); continue; }
                assertThat(link).as("%s %s %s", spec.code(), audience, type).isPresent();
                linked.add(spec.code());
            }
        }
        assertThat(linked).contains("N-08a", "N-13", "N-15", "N-42", "N-47").doesNotContain("N-31");
        // The web's routes (E72): no training path is `/training`, and N-31's OPEN_SETUP (APP only) opens no training screen.
        for (var type : NotificationActionType.values()) {
            for (var audience : NotificationAudience.values()) {
                NotificationLinks.of(audience, type, ALL).ifPresent(link -> assertThat(link.path()).as("%s %s", audience, type).doesNotContain("/training"));
            }
        }
        assertThat(NotificationLinks.of(MEMBER, OPEN_DOG, Map.of("dogId", "a b&c=d/é")).orElseThrow().path()).isEqualTo("/gossos?dogId=a+b%26c%3Dd%2F%C3%A9");
        assertThat(NotificationLinks.of(ADMINS, OPEN_MEMBER, Map.of("memberId", "m/1")).orElseThrow().path()).isEqualTo("/abonats/m%2F1");
        assertThat(NotificationLinks.of(MEMBER, OPEN_DOG, Map.of("dogId", " "))).contains(new NotificationLinks.Link("clubs", "/gossos"));
        assertThat(NotificationLinks.of(null, OPEN_DOG, ALL)).isEmpty(); assertThat(NotificationLinks.of(MEMBER, null, ALL)).isEmpty();
        assertThat(NotificationLinks.of(MEMBER, OPEN_SETUP, null)).isEmpty();
    }
}
