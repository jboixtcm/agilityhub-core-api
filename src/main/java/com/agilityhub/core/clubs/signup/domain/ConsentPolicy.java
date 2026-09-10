package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class ConsentPolicy {
    private ConsentPolicy() { }
    public enum Type { PRIVACY_POLICY, IMAGE_USE }
    public record Legal(String legalTextsVersion) { }
    public record Request(boolean privacyAccepted, String version, Boolean imageGranted) { }
    public record Entry(Type type, boolean granted, String version, Instant acceptedAt, String locale, String ipHash, String source) { }
    public static void check(Request request, Legal legal) {
        if (request == null || !request.privacyAccepted()) { throw SignupValidation.field("consents.privacyPolicy.accepted"); }
        if (!legal.legalTextsVersion().equals(request.version())) { throw new ApiException(ErrorCode.CONSENT_VERSION_OUTDATED); }
        if (request.imageGranted() == null) { throw SignupValidation.field("consents.imageUse.granted"); }
    }
    /** Versions are opaque labels: only an exact current acceptance avoids asking again. */
    public static boolean requiresAcceptance(List<Entry> history, Legal legal) {
        return history.stream().filter(e -> e.type() == Type.PRIVACY_POLICY)
                .max(Comparator.comparing(Entry::acceptedAt))
                .map(e -> !e.granted() || !legal.legalTextsVersion().equals(e.version())).orElse(true);
    }
    public static List<Entry> entries(Request request, Legal legal, boolean addDog, List<Entry> history,
            Clock clock, String locale, String ipHash) {
        if (addDog && request == null && !requiresAcceptance(history, legal)) { return List.of(); }
        check(request, legal);
        Instant at = clock.instant(); String source = addDog ? "APP_ADD_DOG" : "PUBLIC";
        return List.of(new Entry(Type.PRIVACY_POLICY, true, request.version(), at, locale, ipHash, source),
                new Entry(Type.IMAGE_USE, request.imageGranted(), request.version(), at, locale, ipHash, source));
    }
}
