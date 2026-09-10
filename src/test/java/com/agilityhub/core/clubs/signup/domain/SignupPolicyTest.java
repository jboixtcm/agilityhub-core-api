package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
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
    @Test void T_04_08_familyFareUsesEligibleCatalogPlanOrDiscountHint() {
        var standard = new FamilyFareProposal.Fare("single-dog-plan", "standard", new Money(6000, "EUR"));
        var family = new FamilyFareProposal.Fare("two-dog-plan", "family", new Money(9000, "EUR"));
        assertThat(FamilyFareProposal.propose(List.of(DogStatus.ACTIVE, DogStatus.INACTIVE), standard, family, 50).kind())
                .isEqualTo(FamilyFareProposal.Kind.STANDARD);
        assertThat(FamilyFareProposal.propose(List.of(DogStatus.ACTIVE, DogStatus.PENDING), standard, family, 50).kind())
                .isEqualTo(FamilyFareProposal.Kind.FAMILY);
        assertThat(FamilyFareProposal.propose(2, standard, family, 50))
                .isEqualTo(new FamilyFareProposal.Proposal(FamilyFareProposal.Kind.FAMILY, family, null));
        assertThat(FamilyFareProposal.propose(2, standard, null, 50))
                .isEqualTo(new FamilyFareProposal.Proposal(FamilyFareProposal.Kind.STANDARD, standard, 50));
        assertThat(FamilyFareProposal.propose(1, standard, family, 50))
                .isEqualTo(new FamilyFareProposal.Proposal(FamilyFareProposal.Kind.STANDARD, standard, null));
        assertThat(FamilyFareProposal.propose(0, standard, null, 50).discountPercentHint()).isNull();
        error(() -> FamilyFareProposal.propose(-1, standard, family, 50), ErrorCode.VALIDATION_ERROR);
        error(() -> FamilyFareProposal.propose(2, standard, family, -1), ErrorCode.VALIDATION_ERROR);
        error(() -> FamilyFareProposal.propose(2, standard, family, 101), ErrorCode.VALIDATION_ERROR);
    }
    @Test void T_04_10_genderSnapshotsUseProductionIcuBundlesInThreeLocales() throws Exception {
        var source = new IcuMessageSource();
        Map<String,List<String>> welcomes = Map.of(
                "ca", List.of("Benvingut, Pau!", "Benvinguda, Marta!", "Benvingut, Pau!"),
                "es", List.of("¡Bienvenido, Pau!", "¡Bienvenida, Marta!", "¡Bienvenido, Pau!"),
                "en", List.of("Welcome, Pau!", "Welcome, Marta!", "Welcome, Pau!"));
        Map<String,List<String>> warnings = Map.of(
                "ca", List.of("No autoritza l’ús de la seva imatge: no publiqueu fotos on surti ell.",
                        "No autoritza l’ús de la seva imatge: no publiqueu fotos on surti ella.",
                        "No autoritza l’ús de la seva imatge: no publiqueu fotos on surti ell."),
                "es", List.of("No autoriza el uso de su imagen: no publiquéis fotos donde salga él.",
                        "No autoriza el uso de su imagen: no publiquéis fotos donde salga ella.",
                        "No autoriza el uso de su imagen: no publiquéis fotos donde salga él."),
                "en", List.of("Image use is not authorized: do not publish photos where he appears.",
                        "Image use is not authorized: do not publish photos where she appears.",
                        "Image use is not authorized: do not publish photos where they appear."));
        var genders = List.of("MALE", "FEMALE", "OTHER");
        for (String locale : welcomes.keySet()) {
            for (int i = 0; i < genders.size(); i++) {
                var args = Map.of("gender", genders.get(i), "name", i == 1 ? "Marta" : "Pau");
                assertThat(source.format("signup.welcome", args, Locale.forLanguageTag(locale))).isEqualTo(welcomes.get(locale).get(i));
                assertThat(source.format("signup.imageWarning", args, Locale.forLanguageTag(locale))).isEqualTo(warnings.get(locale).get(i));
            }
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
    @Test void T_04_17_signupStateCoversPublicReadmissionAndAddDogWithoutReprovisioning() {
        var draft = new SignupState(SignupState.MemberStatus.DRAFT, SignupState.Source.PUBLIC, SignupState.Resolution.NONE, false);
        var pending = draft.apply(SignupState.Command.SUBMIT).state();
        assertThat(pending.memberStatus()).isEqualTo(SignupState.MemberStatus.PENDING);
        assertThat(pending.readmission()).isFalse();
        assertThat(pending.apply(SignupState.Command.EDIT).state()).isEqualTo(pending);
        assertThat(pending.apply(SignupState.Command.REMIND).state()).isEqualTo(pending);
        var active = pending.apply(SignupState.Command.VALIDATE);
        assertThat(active.provisionMembership()).isTrue();
        assertThat(active.dogStatus()).isEqualTo(SignupState.DogStatus.ACTIVE);
        assertThat(active.state().memberStatus()).isEqualTo(SignupState.MemberStatus.ACTIVE);
        error(() -> active.state().apply(SignupState.Command.VALIDATE), ErrorCode.INVALID_STATE);
        error(() -> active.state().apply(SignupState.Command.REJECT), ErrorCode.INVALID_STATE);
        var left = pending.apply(SignupState.Command.REJECT).state();
        assertThat(left.memberStatus()).isEqualTo(SignupState.MemberStatus.LEFT);
        var readmitted = left.apply(SignupState.Command.SUBMIT).state();
        assertThat(readmitted.readmission()).isTrue();
        assertThat(readmitted.apply(SignupState.Command.VALIDATE).state().readmission()).isTrue();
        var dogPending = active.state().apply(SignupState.Command.ADD_DOG);
        assertThat(dogPending.dogStatus()).isEqualTo(SignupState.DogStatus.PENDING);
        for (var command : List.of(SignupState.Command.VALIDATE, SignupState.Command.REJECT)) {
            var decision = dogPending.state().apply(command);
            assertThat(decision.state().memberStatus()).isEqualTo(SignupState.MemberStatus.ACTIVE);
            assertThat(decision.provisionMembership()).isFalse();
            assertThat(decision.dogStatus()).isEqualTo(command == SignupState.Command.VALIDATE ? SignupState.DogStatus.ACTIVE : SignupState.DogStatus.INACTIVE);
        }
        error(() -> pending.apply(SignupState.Command.SUBMIT), ErrorCode.INVALID_STATE);
        error(() -> pending.apply(SignupState.Command.ADD_DOG), ErrorCode.INVALID_STATE);
        error(() -> left.apply(SignupState.Command.REJECT), ErrorCode.INVALID_STATE);
        error(() -> draft.apply(SignupState.Command.VALIDATE), ErrorCode.INVALID_STATE);
        error(() -> new SignupState(SignupState.MemberStatus.LEFT, SignupState.Source.APP_ADD_DOG, SignupState.Resolution.PENDING, false)
                .apply(SignupState.Command.EDIT), ErrorCode.INVALID_STATE);
    }
    @Test void T_04_22_paymentStateTransitionMatrixMatchesS04AndRejectionRetainsPaid() {
        var allowed = Map.of(
                UpfrontPaymentStatus.DUE, Set.of(UpfrontPaymentStatus.CHECKOUT_PENDING, UpfrontPaymentStatus.PAID, UpfrontPaymentStatus.PARTIAL, UpfrontPaymentStatus.CANCELLED),
                UpfrontPaymentStatus.CHECKOUT_PENDING, Set.of(UpfrontPaymentStatus.PAID, UpfrontPaymentStatus.DUE, UpfrontPaymentStatus.CANCELLED),
                UpfrontPaymentStatus.PARTIAL, Set.of(UpfrontPaymentStatus.PAID, UpfrontPaymentStatus.CANCELLED),
                UpfrontPaymentStatus.PAID, Set.of(UpfrontPaymentStatus.REFUNDED));
        for (var before : UpfrontPaymentStatus.values()) {
            for (var after : UpfrontPaymentStatus.values()) {
                if (allowed.getOrDefault(before, Set.of()).contains(after)) { assertThat(before.transitionTo(after)).isEqualTo(after); }
                else { error(() -> before.transitionTo(after), ErrorCode.INVALID_STATE); }
            }
            assertThat(before.onRejection()).isEqualTo(switch (before) {
                case DUE, PARTIAL, CHECKOUT_PENDING -> UpfrontPaymentStatus.CANCELLED;
                default -> before;
            });
        }
    }
}
