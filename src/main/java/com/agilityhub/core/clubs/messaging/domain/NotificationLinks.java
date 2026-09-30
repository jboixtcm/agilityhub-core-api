package com.agilityhub.core.clubs.messaging.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R-11-11 «Correu: l'acció es tradueix en un enllaç profund a la mateixa ruta»: the route a native action opens, with the
 * action's own parameters, in the app of the audience — the member app (`clubs`) for `MEMBER` and `INSTRUCTORS` (the
 * instructor screens live there), the back office (`clubs-admin`) for `ADMINS`. The routes are the web's (ruling E72: where
 * a spec screen's path and the web's route differ, the web's wins, because it is what people open):
 * <ul>
 * <li>`MEMBER`: `CHANGE_CLASS` → 04 `/reservar?dogId=` (the dog preselected); `CLAIM_SEAT` → 06/29
 * `/reservar/confirmar?waitlistEntryId=&classSessionId=&dogId=` (the app chains `POST /seat-holds` + claim, S08 R-08-15);
 * `OPEN_BOOKING` → 07 `/reserves/{bookingId}`, a training booking → 08's detail `/entrenaments/{trainingBookingId}` (S09
 * §2), otherwise 04; `OPEN_DOG` → 13 `/gossos?dogId=`; `OPEN_TASKS` → 13 `/gossos?dogId=&taskId=`; `OPEN_INVOICES` → 12
 * `/rebuts?invoiceId=` (until E8 builds the receipts screen); `OPEN_ACTIVITY` → `/activitats/{activityId}`; `OPEN_EXPORT` →
 * 12 `/perfil`. `OPEN_SETUP` (N-31, a new course on a ring; APP only) opens no training screen and has no e-mail route.</li>
 * <li>`INSTRUCTORS`: a class (`CHANGE_CLASS`, `OPEN_BOOKING`) → 21 `/instructor/classes/{classSessionId}`; `OPEN_DOG` → 22
 * `/instructor/alumnes/{dogId}`; `OPEN_TASKS` → 26 `/instructor/alumnes/{dogId}/tasques`; otherwise 23 `/instructor/avui`.</li>
 * <li>`ADMINS`: `OPEN_SIGNUP` → D2 `/preinscripcions/{memberId}` (without a member, the pending card of D1 `/tauler`);
 * `OPEN_MEMBER` → D10 `/abonats/{memberId}`; `OPEN_INVOICES` → D6 `/facturacio`; `OPEN_DOG` → `/gossos/{dogId}`; a class
 * (`CHANGE_CLASS`, `OPEN_BOOKING`) → D4 `/calendari`; `OPEN_ACTIVITY` → D7 `/activitats/{activityId}`; `OPEN_JOBS` (N-42)
 * → D11's processes `/parametres#processos` (S15 §2); otherwise D1 `/tauler`.</li>
 * </ul>
 * `OPEN_CHALLENGE` (R2) and an action without its required id have no route: the e-mail then carries no button.
 */
public final class NotificationLinks {
    public static final String CLUBS = "clubs", CLUBS_ADMIN = "clubs-admin";
    private NotificationLinks() { }

    /** A route of one app: `app` is the `Club.domains[].app` whose origin prefixes `path` (path and query, already encoded). */
    public record Link(String app, String path) { }

    public static Optional<Link> of(NotificationAudience audience, NotificationActionType type, Map<String, String> params) {
        if (type == null || audience == null) { return Optional.empty(); }
        var p = params == null ? Map.<String, String>of() : params;
        return Optional.ofNullable(switch (audience) {
            case MEMBER, APPLICANT -> member(type, p);
            case INSTRUCTORS -> instructor(type, p);
            case ADMINS -> admin(type, p);
        });
    }

    private static Link member(NotificationActionType type, Map<String, String> p) {
        String path = switch (type) {
            case CHANGE_CLASS -> query("/reservar", p, "dogId");
            case CLAIM_SEAT -> p.get("waitlistEntryId") == null ? null : query("/reservar/confirmar", p, "waitlistEntryId", "classSessionId", "dogId");
            case OPEN_BOOKING -> p.get("bookingId") != null ? "/reserves/" + encode(p.get("bookingId"))
                    : p.get("trainingBookingId") != null ? "/entrenaments/" + encode(p.get("trainingBookingId")) : query("/reservar", p, "dogId");
            case OPEN_DOG -> query("/gossos", p, "dogId");
            case OPEN_TASKS -> query("/gossos", p, "dogId", "taskId");
            case OPEN_INVOICES -> query("/rebuts", p, "invoiceId");
            case OPEN_ACTIVITY -> p.get("activityId") == null ? null : "/activitats/" + encode(p.get("activityId"));
            case OPEN_EXPORT -> "/perfil";
            case OPEN_SETUP, OPEN_SIGNUP, OPEN_MEMBER, OPEN_JOBS, OPEN_CHALLENGE -> null;
        };
        return path == null ? null : new Link(CLUBS, path);
    }
    private static Link instructor(NotificationActionType type, Map<String, String> p) {
        String dog = p.get("dogId"), session = p.get("classSessionId");
        String path = switch (type) {
            case CHANGE_CLASS, OPEN_BOOKING -> session == null ? "/instructor/avui" : "/instructor/classes/" + encode(session);
            case OPEN_DOG -> dog == null ? "/instructor/avui" : "/instructor/alumnes/" + encode(dog);
            case OPEN_TASKS -> dog == null ? "/instructor/avui" : "/instructor/alumnes/" + encode(dog) + "/tasques";
            case OPEN_CHALLENGE -> null;
            default -> "/instructor/avui";
        };
        return path == null ? null : new Link(CLUBS, path);
    }
    private static Link admin(NotificationActionType type, Map<String, String> p) {
        String member = p.get("memberId");
        String path = switch (type) {
            case OPEN_SIGNUP -> member == null ? "/tauler" : "/preinscripcions/" + encode(member);
            case OPEN_MEMBER -> member == null ? "/abonats" : "/abonats/" + encode(member);
            case OPEN_INVOICES -> "/facturacio";
            case OPEN_DOG, OPEN_TASKS -> p.get("dogId") == null ? "/gossos" : "/gossos/" + encode(p.get("dogId"));
            case CHANGE_CLASS, CLAIM_SEAT, OPEN_BOOKING -> "/calendari";
            case OPEN_ACTIVITY -> p.get("activityId") == null ? "/activitats" : "/activitats/" + encode(p.get("activityId"));
            case OPEN_JOBS -> "/parametres#processos";
            case OPEN_CHALLENGE -> null;
            default -> "/tauler";
        };
        return path == null ? null : new Link(CLUBS_ADMIN, path);
    }

    /** `path?name=value&…` with the named parameters the action has, in this order. */
    private static String query(String path, Map<String, String> params, String... names) {
        var query = new StringBuilder();
        for (String name : List.of(names)) {
            String value = params.get(name);
            if (value == null || value.isBlank()) { continue; }
            query.append(query.isEmpty() ? '?' : '&').append(name).append('=').append(encode(value));
        }
        return path + query;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
