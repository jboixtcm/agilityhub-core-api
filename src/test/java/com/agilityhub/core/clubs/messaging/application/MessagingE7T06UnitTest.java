package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateProvider;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * E7-T06 without a database: step 1's text corrections (`gender` keys, N-02's link sentence) and the upgrade's choices and
 * failure paths (a concurrent D9 save, a template that cannot be written, a club that no longer exists, a `bin/core`
 * command); step 5's `seedArguments`, the seed's selectors only.
 */
class MessagingE7T06UnitTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T08:00:00Z"), ZoneOffset.UTC);
    static final ClubConfig CA_ES = new ClubConfig(new ClubConfig.ClubView("club", "club", "Club Agility Exemple", List.of("ca", "es"), "ca", "Europe/Madrid", "EUR", null,
            null, "ACTIVE", null), Map.of(), Set.of(), null, Map.of());

    static LocalizedText text(String... localeAndText) {
        var values = new LinkedHashMap<String, String>();
        for (int i = 0; i < localeAndText.length; i += 2) { values.put(localeAndText[i], localeAndText[i + 1]); }
        return new LocalizedText(values, "ca");
    }
    static MessageTemplate template(String code, TemplateKind kind, LocalizedText title, LocalizedText body, boolean customized, TemplateStatus status) {
        var category = code == null ? NotificationCategory.CLUB_NEWS : NotificationCatalog.byCode(code).orElseThrow().category();
        return new MessageTemplate("t-" + code, "club", code, kind, category, title, body, null, TemplateIcon.mail, TemplateColor.OK, Map.of(), true, false, customized,
                status, 0L, Instant.EPOCH, "system:notification-engine", Instant.EPOCH, "system:notification-engine");
    }
    static MessageTemplate seed(String code) {
        var seeded = MessageTemplateSeed.load().of(code).orElseThrow();
        return new MessageTemplate(null, "club", code, TemplateKind.CATALOG, NotificationCatalog.byCode(code).orElseThrow().category(), seeded.title(List.of("ca", "es"), "ca"),
                seeded.body(List.of("ca", "es"), "ca"), seeded.smsBody(List.of("ca", "es"), "ca"), seeded.icon(), seeded.color(), seeded.matrix(), true, false, false,
                TemplateStatus.ACTIVE, null, Instant.EPOCH, "seed", Instant.EPOCH, "seed");
    }

    @Test void E7_T06_theGenderKeysOfEverySelectInLowerCase() {
        assertThat(TemplateUpgrade.lowerGenderKeys("{gender, select, FEMALE {Benvinguda} other {Benvingut}}, {member_first_name}!"))
                .isEqualTo("{gender, select, female {Benvinguda} other {Benvingut}}, {member_first_name}!");
        // Nested branches, MALE and OTHER, a second select, spacing; other selects and the branches' words untouched.
        assertThat(TemplateUpgrade.lowerGenderKeys("{ gender , select , FEMALE {La {mode, select, FIFO {FEMALE}  other {x}}} MALE {El}  OTHER {L'}} i {gender,select,Female{a} other{e}}"))
                .isEqualTo("{ gender , select , female {La {mode, select, FIFO {FEMALE}  other {x}}} male {El}  other {L'}} i {gender,select,female{a} other{e}}");
        assertThat(TemplateUpgrade.lowerGenderKeys("Hola {member_first_name}")).isEqualTo("Hola {member_first_name}");
        assertThat(TemplateUpgrade.lowerGenderKeys(null)).isNull();
        // A select cut short, or a key without its branch: what can be read is lowered, never beyond.
        assertThat(TemplateUpgrade.lowerGenderKeys("{gender, select, FEMALE")).isEqualTo("{gender, select, FEMALE");
        assertThat(TemplateUpgrade.lowerGenderKeys("{gender, select, FEMALE}")).isEqualTo("{gender, select, FEMALE}");
    }

    @Test void E7_T06_n02LosesTheSentenceOfItsLinkAndKeepsTheRest() {
        // E7-T02's copy and round 1's seed, in the three languages.
        assertThat(TemplateUpgrade.withoutLinkSentence("La teva alta a {club_name} està validada. Entra al teu compte amb aquest enllaç."))
                .isEqualTo("La teva alta a {club_name} està validada.");
        assertThat(TemplateUpgrade.withoutLinkSentence("Tu alta en {club_name} está validada. Accede a tu cuenta con este enlace.")).isEqualTo("Tu alta en {club_name} está validada.");
        assertThat(TemplateUpgrade.withoutLinkSentence("Your membership at {club_name} is active. Use the link to access your account."))
                .isEqualTo("Your membership at {club_name} is active.");
        assertThat(TemplateUpgrade.withoutLinkSentence("Ja tens accés a l'app del club. Entra-hi amb aquest enllaç: [[link]].")).isEqualTo("Ja tens accés a l'app del club.");
        // A club's own sentence with the link, wherever it is (first, middle, last without a full stop, on its own line).
        assertThat(TemplateUpgrade.withoutLinkSentence("Clica aquí: [[ link ]]! Benvinguda al club.")).isEqualTo("Benvinguda al club.");
        assertThat(TemplateUpgrade.withoutLinkSentence("Hola. El teu enllaç és {link}? Fins aviat.")).isEqualTo("Hola. Fins aviat.");
        assertThat(TemplateUpgrade.withoutLinkSentence("Hola.\nEntra: [[link]]\nFins aviat.")).isEqualTo("Hola.\n\nFins aviat.");
        assertThat(TemplateUpgrade.withoutLinkSentence("Ja ets dins. Entra: [[link]]")).isEqualTo("Ja ets dins.");
        assertThat(TemplateUpgrade.withoutLinkSentence("Sense enllaç.")).isEqualTo("Sense enllaç.");
        assertThat(TemplateUpgrade.withoutLinkSentence(null)).isNull();
        // A removal that would leave a text that does not parse keeps it.
        assertThat(TemplateUpgrade.withoutLinkSentence("Hola {gender, select, female {Entra: [[link]]. Adeu} other {x}}"))
                .isEqualTo("Hola {gender, select, female {Entra: [[link]]. Adeu} other {x}}");
        assertThat(TemplateUpgrade.withoutLinkSentence("{gender, select, female {Hola. Entra: [[link]]. Adeu} other {x}}"))
                .isEqualTo("{gender, select, female {Hola. Adeu} other {x}}");
    }

    @Test void E7_T06_whatTheUpgradeWritesForEachKindOfTemplate() {
        var e7t02 = template("N-02", TemplateKind.CATALOG, text("ca", "{gender, select, FEMALE {Benvinguda} other {Benvingut}}, {member_first_name}!", "es", "¡Hola!", "en", "Hi!"),
                text("ca", "La teva alta a {club_name} està validada. Entra al teu compte amb aquest enllaç.", "es", "Hola.", "en", "Hi."), false, TemplateStatus.ACTIVE);
        // Never edited: the seed's texts in the club's languages, customized stays false; the rest as stored.
        var seeded = TemplateUpgrade.upgraded(e7t02, seed("N-02"), CA_ES);
        assertThat(seeded.title().values()).isEqualTo(seed("N-02").title().values()); assertThat(seeded.body().values().get("ca")).isEqualTo("Ja tens accés a l'app del club.");
        assertThat(seeded.customized()).isFalse(); assertThat(seeded.version()).isZero(); assertThat(seeded.icon()).isEqualTo(TemplateIcon.mail);
        assertThat(TemplateUpgrade.upgraded(seeded, seed("N-02"), CA_ES)).isSameAs(seeded); // idempotent
        // Edited: the club's words, in the club's languages, keys lowered, link sentence gone; customized recomputed.
        var edited = template("N-02", TemplateKind.CATALOG, e7t02.title(), e7t02.body(), true, TemplateStatus.ACTIVE);
        var corrected = TemplateUpgrade.upgraded(edited, seed("N-02"), CA_ES);
        assertThat(corrected.title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Benvinguda} other {Benvingut}}, {member_first_name}!", "es", "¡Hola!"));
        assertThat(corrected.body().values()).isEqualTo(Map.of("ca", "La teva alta a {club_name} està validada.", "es", "Hola."));
        assertThat(corrected.customized()).isTrue();
        // An edited body that was the link sentence only takes the seed's text of that language (a body is required).
        var onlyLink = template("N-02", TemplateKind.CATALOG, text("ca", "Hola!"), text("ca", "Entra-hi amb aquest enllaç: [[link]]."), true, TemplateStatus.ACTIVE);
        assertThat(TemplateUpgrade.upgraded(onlyLink, seed("N-02"), CA_ES).body().values()).isEqualTo(Map.of("ca", "Ja tens accés a l'app del club."));
        // Edited only by the old upper-case keys: once lowered it is the seed again, customized false.
        var seed02 = seed("N-02");
        var upperCase = template("N-02", TemplateKind.CATALOG, new LocalizedText(Map.of("ca", seed02.title().values().get("ca").replace("female", "FEMALE"),
                "es", seed02.title().values().get("es")), "ca"), seed02.body(), true, TemplateStatus.ACTIVE);
        assertThat(upperCase.title().values().get("ca")).contains("FEMALE");
        assertThat(TemplateUpgrade.upgraded(upperCase, seed02, CA_ES).customized()).isFalse();
        // CUSTOM: the keys lowered, nothing else; an up-to-date one, an archived one, a code without seed: untouched.
        var custom = template(null, TemplateKind.CUSTOM, text("ca", "{gender, select, FEMALE {Estimada} other {Estimat}}"), text("ca", "Sopar."), false, TemplateStatus.ACTIVE);
        assertThat(TemplateUpgrade.upgraded(custom, null, CA_ES).title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Estimada} other {Estimat}}"));
        var current = template(null, TemplateKind.CUSTOM, text("ca", "Hola"), text("ca", "Sopar."), false, TemplateStatus.ACTIVE);
        assertThat(TemplateUpgrade.upgraded(current, null, CA_ES)).isSameAs(current);
        var archived = template(null, TemplateKind.CUSTOM, custom.title(), custom.body(), false, TemplateStatus.ARCHIVED);
        assertThat(TemplateUpgrade.upgraded(archived, null, CA_ES)).isSameAs(archived);
        assertThat(TemplateUpgrade.upgraded(e7t02, null, CA_ES)).isSameAs(e7t02);
    }

    @Test void E7_T06_aConcurrentSaveAFailedWriteAMissingClubAndACommand() {
        var repository = mock(MessageTemplateRepository.class); var configs = mock(ClubConfigService.class);
        var provider = new TemplateProvider(repository, MessageTemplateSeed.load(), CLOCK, null);
        var e7t02 = template("N-02", TemplateKind.CATALOG, text("ca", "{gender, select, FEMALE {Benvinguda} other {Benvingut}}!"), text("ca", "Validada. Entra al teu compte amb aquest enllaç."),
                false, TemplateStatus.ACTIVE);
        // A D9 save between the read and the write: STALE_VERSION, read again (now edited), corrected from there.
        var saved = new MessageTemplate(e7t02.id(), "club", "N-02", TemplateKind.CATALOG, e7t02.category(), e7t02.title(), text("ca", "Ja ets dels nostres. Entra al teu compte amb aquest enllaç."),
                null, e7t02.icon(), e7t02.color(), Map.of(), true, true, true, TemplateStatus.ACTIVE, 1L, Instant.EPOCH, "seed", Instant.EPOCH, "admin");
        var broken = template("N-09", TemplateKind.CATALOG, text("ca", "x"), text("ca", "y"), false, TemplateStatus.ACTIVE);
        when(repository.clubIds()).thenReturn(List.of("club", "gone"));
        when(configs.get("club")).thenReturn(CA_ES);
        when(configs.get("gone")).thenThrow(new ApiException(ErrorCode.CLUB_NOT_FOUND));
        when(repository.findAll()).thenReturn(List.of(e7t02, broken));
        when(repository.findById(e7t02.id())).thenReturn(Optional.of(saved));
        when(repository.update(any(), anyLong())).thenAnswer(call -> {
            MessageTemplate next = call.getArgument(0); long version = call.getArgument(1);
            if (next.code().equals("N-09")) { throw new IllegalStateException("write failed"); }
            if (version == 0L) { throw new ApiException(ErrorCode.STALE_VERSION); }
            return next;
        });
        var upgrade = new TemplateUpgrade(repository, provider, configs, CLOCK, "");
        var result = upgrade.upgradeAll();
        assertThat(result).isEqualTo(new TemplateUpgrade.Result(0, 1, 1));
        var written = org.mockito.ArgumentCaptor.forClass(MessageTemplate.class);
        verify(repository, times(1)).update(written.capture(), org.mockito.ArgumentMatchers.eq(1L));
        assertThat(written.getValue().body().values()).isEqualTo(Map.of("ca", "Ja ets dels nostres."));
        assertThat(written.getValue().updatedBy()).isEqualTo("system:template-upgrade"); assertThat(written.getValue().updatedAt()).isEqualTo(CLOCK.instant());
        // A save that keeps answering STALE_VERSION gives up after three reads (one failure); one deleted meanwhile is nothing.
        reset(repository);
        when(repository.findAll()).thenReturn(List.of(e7t02));
        when(repository.findById(e7t02.id())).thenReturn(Optional.of(e7t02), Optional.of(e7t02), Optional.empty());
        when(repository.update(any(), anyLong())).thenThrow(new ApiException(ErrorCode.STALE_VERSION));
        try (var tenant = TenantContext.open("club")) { assertThat(upgrade.upgrade(CA_ES)).isEqualTo(new TemplateUpgrade.Result(0, 0, 1)); }
        when(repository.findById(e7t02.id())).thenReturn(Optional.empty());
        try (var tenant = TenantContext.open("club")) { assertThat(upgrade.upgrade(CA_ES)).isEqualTo(new TemplateUpgrade.Result(0, 0, 0)); }
        // Another failure of the club's configuration is not swallowed; a `bin/core` command never reads anything.
        when(repository.clubIds()).thenReturn(List.of("club"));
        when(configs.get("club")).thenThrow(new ApiException(ErrorCode.INTERNAL_ERROR));
        assertThatThrownBy(upgrade::afterSingletonsInstantiated).isInstanceOf(ApiException.class);
        var command = mock(MessageTemplateRepository.class);
        new TemplateUpgrade(command, provider, configs, CLOCK, "club:apply").afterSingletonsInstantiated();
        verifyNoInteractions(command);
    }

    /**
     * Step 5 (claude review #3): `seedArguments` — what a save accepts besides the row's variables — is the seed's `select` and
     * `plural` argument names only. Before the fix it was every ICU argument of the seed: N-08b's `class_description` and N-32c's
     * `date` too, a hidden second variable list.
     */
    @Test void E7_T06_seedArgumentsAreTheSeedsSelectorsOnly() {
        var provider = new TemplateProvider(null, MessageTemplateSeed.load(), CLOCK, null);
        var service = new MessageTemplateService(null, provider, null, null, null, null, null, CLOCK, null);
        assertThat(service.seedArguments("N-01")).containsExactly("has_upfront");
        assertThat(service.seedArguments("N-15")).containsExactly("mode");
        assertThat(service.seedArguments("N-16")).containsExactly("auto_cancel");
        assertThat(service.seedArguments("N-08b")).isEmpty(); assertThat(service.seedArguments("N-32c")).isEmpty();
        assertThat(service.rules(TemplateKind.CATALOG, "N-08b", NotificationCategory.CLUB_CHANGES).icuNames()).doesNotContain("class_description");
        assertThat(service.rules(TemplateKind.CATALOG, "N-32c", NotificationCategory.CLUB_CHANGES).icuNames()).doesNotContain("date");
        assertThat(service.seedArguments("N-99")).isEmpty();
    }
}
