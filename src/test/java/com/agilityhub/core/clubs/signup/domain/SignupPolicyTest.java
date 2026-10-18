package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.agilityhub.core.clubs.signup.domain.SignupContactsTest.error;
import static com.agilityhub.core.clubs.signup.domain.FamilyHolderMatcher.*;

class SignupPolicyTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-17T12:00:00Z"), ZoneOffset.UTC);
    private Candidate candidate(String id, MemberStatus status, DogStatus dogStatus) {
        return new Candidate("club", id, status, "Marta", "Roca", "Soler", List.of(new Dog("Kiwi", dogStatus)));
    }

    @Test void T_04_08_holderMatchingNormalizesNamesAndRequiresUniqueEligibleHolder() {
        var marta = candidate("marta-id", MemberStatus.ACTIVE, DogStatus.ACTIVE);
        var candidates = List.of(marta);
        for (String holder : List.of("Marta Roca", "marta roca", "  MÁRTA   RÓCA ", "Roca Marta", "Marta Roca Soler")) {
            var match = FamilyHolderMatcher.match("club", holder, "KIWI", candidates).orElseThrow();
            assertThat(match.holderMemberId()).isEqualTo("marta-id");
            assertThat(match.publicResult()).isEqualTo(new PublicResult(Outcome.FOUND, "Marta R."));
        }
        for (String holder : Arrays.asList(null, "", "Marta", "Marta Marta", "Mart Roca", "Marta Roc")) {
            assertThat(FamilyHolderMatcher.lookup("club", holder, "Kiwi", candidates)).isEqualTo(new PublicResult(Outcome.NOT_FOUND, null));
        }
        for (String dog : Arrays.asList(null, "", "Kiwy", "Kiw")) {
            assertThat(FamilyHolderMatcher.match("club", "Marta Roca", dog, candidates)).isEmpty();
        }
        assertThat(FamilyHolderMatcher.match("club", "Marta Roca", "Kiwi", List.of(marta, candidate("second", MemberStatus.PENDING, DogStatus.ACTIVE)))).isEmpty();
        assertThat(FamilyHolderMatcher.match("other-club", "Marta Roca", "Kiwi", candidates)).isEmpty();
        assertThat(FamilyHolderMatcher.match("club", "Marta Roca", "Kiwi", List.of(candidate("left", MemberStatus.LEFT, DogStatus.ACTIVE)))).isEmpty();
        assertThat(FamilyHolderMatcher.match("club", "Marta Roca", "Kiwi", List.of(candidate("inactive-dog", MemberStatus.ACTIVE, DogStatus.INACTIVE)))).isEmpty();
        assertThat(FamilyHolderMatcher.match("club", "Marta Roca", "Kiwi", List.of(candidate("pending", MemberStatus.PENDING, DogStatus.PENDING)))).isPresent();
        var noSecondName = new Candidate("club", "id", MemberStatus.ACTIVE, "Marta", "Roca", null, List.of(new Dog("Kiwi", DogStatus.ACTIVE)));
        assertThat(FamilyHolderMatcher.match("club", "Marta Roca", "Kiwi", List.of(noSecondName))).isPresent();
    }
    @Test void T_04_10_genderSnapshotsUseProductionIcuBundlesInThreeLocales() throws Exception {
        var source = new IcuMessageSource();
        Map<String,List<String>> welcomes = Map.of(
                "ca", List.of("Benvingut, Pau!", "Benvinguda, Marta!", "Benvingut, Pau!"),
                "es", List.of("¡Bienvenido, Pau!", "¡Bienvenida, Marta!", "¡Bienvenido, Pau!"),
                "en", List.of("Welcome, Pau!", "Welcome, Marta!", "Welcome, Pau!"));
        Map<String,String> bodies = Map.of("ca", "La teva alta a Example Club està validada. Entra al teu compte amb aquest enllaç.",
                "es", "Tu alta en Example Club está validada. Accede a tu cuenta con este enlace.",
                "en", "Your membership at Example Club is active. Use the link to access your account.");
        // E3-T10 step 11: the keys and variables the N-02 delivery really renders (SignupNotifications → MagicLinkService →
        // `notif.N-02.*` with `member_first_name`, `gender`, `club_name`). The D2 image warning is rendered by the web
        // (`admin-census:signup.imageConsentWarning`, T-04-33), so the api has no key for it.
        var genders = List.of("MALE", "FEMALE", "OTHER");
        for (String locale : welcomes.keySet()) {
            for (int i = 0; i < genders.size(); i++) {
                var args = Map.of("gender", genders.get(i), "member_first_name", i == 1 ? "Marta" : "Pau", "club_name", "Example Club");
                assertThat(source.format("notif.N-02.title", args, Locale.forLanguageTag(locale))).as(locale + " " + genders.get(i)).isEqualTo(welcomes.get(locale).get(i));
                assertThat(source.format("notif.N-02.body", args, Locale.forLanguageTag(locale))).isEqualTo(bodies.get(locale));
            }
        }
        for (String locale : welcomes.keySet()) {
            var messages = new java.util.Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("src/main/resources/messages/messages_" + locale + ".properties"))) { messages.load(reader); }
            assertThat(messages.stringPropertyNames()).as(locale).doesNotContain("signup.welcome", "signup.imageWarning");
        }
    }
    @Test void T_04_15_consentRequiresAcceptanceAndCurrentVersionAndProducesAppendOnlyEntries() {
        var legal = new ConsentPolicy.Legal("2026-09");
        error(() -> ConsentPolicy.check(null, legal), ErrorCode.VALIDATION_ERROR);
        error(() -> ConsentPolicy.check(new ConsentPolicy.Request(false, "2026-09", false), legal), ErrorCode.VALIDATION_ERROR);
        error(() -> ConsentPolicy.check(new ConsentPolicy.Request(true, "2026-08", true), legal), ErrorCode.CONSENT_VERSION_OUTDATED);
        error(() -> ConsentPolicy.check(new ConsentPolicy.Request(true, null, true), legal), ErrorCode.CONSENT_VERSION_OUTDATED);
        error(() -> ConsentPolicy.check(new ConsentPolicy.Request(true, "2026-09", null), legal), ErrorCode.VALIDATION_ERROR);
        var entries = ConsentPolicy.entries(new ConsentPolicy.Request(true, "2026-09", false), legal, false, List.of(), clock, "ca", "fictional-ip");
        assertThat(entries).containsExactly(
                new ConsentPolicy.Entry(ConsentPolicy.Type.PRIVACY_POLICY, true, "2026-09", clock.instant(), "ca", "fictional-ip", "PUBLIC"),
                new ConsentPolicy.Entry(ConsentPolicy.Type.IMAGE_USE, false, "2026-09", clock.instant(), "ca", "fictional-ip", "PUBLIC"));
        assertThatThrownBy(() -> entries.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ConsentPolicy.entries(null, legal, true, entries, clock, "ca", "fictional-ip")).isEmpty();
        assertThat(ConsentPolicy.requiresAcceptance(entries, legal)).isFalse();
        assertThat(ConsentPolicy.requiresAcceptance(entries, new ConsentPolicy.Legal("2027-01"))).isTrue();
        assertThat(ConsentPolicy.requiresAcceptance(List.of(), legal)).isTrue();
        var newLegal = new ConsentPolicy.Legal("2027-01");
        error(() -> ConsentPolicy.entries(null, newLegal, true, entries, clock, "ca", "fictional-ip"), ErrorCode.VALIDATION_ERROR);
        var renewal = ConsentPolicy.entries(new ConsentPolicy.Request(true, "2027-01", true), newLegal, true, entries, clock, "en", "fictional-ip");
        assertThat(renewal.getFirst().source()).isEqualTo("APP_ADD_DOG");
        assertThat(entries.getFirst().version()).isEqualTo("2026-09");
        var revocation = new ConsentPolicy.Entry(ConsentPolicy.Type.PRIVACY_POLICY, false, "2026-09", clock.instant().plusSeconds(1), "ca", "fictional-ip", "PUBLIC");
        assertThat(ConsentPolicy.requiresAcceptance(List.of(revocation, entries.getFirst()), legal)).isTrue();
        error(() -> ConsentPolicy.entries(null, legal, false, entries, clock, "ca", "fictional-ip"), ErrorCode.VALIDATION_ERROR);
    }
}
