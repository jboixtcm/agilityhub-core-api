package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateProvider;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
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
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
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
import java.util.function.Supplier;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * E7-T06 without a database: step 1's text corrections (`gender` keys, N-02's link sentence, round 2's N-08b
 * `{class_description}`) and the upgrade's choices — corrections only, never a language dropped (round 2, ruling E81) — and
 * failure paths (a concurrent D9 save, a template that cannot be written, a club that no longer exists, a club that fails, a
 * `bin/core` command), with `MessageTemplateChanged` and the audit for each change only; step 5's `seedArguments`, the
 * seed's selectors only.
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
        return template(code, kind, title, body, null, customized, status);
    }
    static MessageTemplate template(String code, TemplateKind kind, LocalizedText title, LocalizedText body, LocalizedText sms, boolean customized, TemplateStatus status) {
        var category = code == null ? NotificationCategory.CLUB_NEWS : NotificationCatalog.byCode(code).orElseThrow().category();
        return new MessageTemplate("t-" + code, "club", code, kind, category, title, body, sms, TemplateIcon.mail, TemplateColor.OK, Map.of(), true, false, customized,
                status, 0L, Instant.EPOCH, "system:notification-engine", Instant.EPOCH, "system:notification-engine");
    }
    /** The code's seed in these languages, as the upgrade computes it (the club's and the template's own). */
    static MessageTemplate seed(String code, String... languages) {
        var seeded = MessageTemplateSeed.load().of(code).orElseThrow(); var locales = languages.length == 0 ? List.of("ca", "es") : List.of(languages);
        return new MessageTemplate(null, "club", code, TemplateKind.CATALOG, NotificationCatalog.byCode(code).orElseThrow().category(), seeded.title(locales, "ca"),
                seeded.body(locales, "ca"), seeded.smsBody(locales, "ca"), seeded.icon(), seeded.color(), seeded.matrix(), true, false, false,
                TemplateStatus.ACTIVE, null, Instant.EPOCH, "seed", Instant.EPOCH, "seed");
    }
    /** A transaction double that runs the work, as `MessagingTransactions.write` does outside any transaction. */
    static MessagingTransactions transactions() {
        var transactions = mock(MessagingTransactions.class);
        when(transactions.write(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        return transactions;
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

    /**
     * Round 2 (review #3, ruling E81): N-08b's `{class_description}`, which E7-T03's seed printed and which is no N-08b variable,
     * goes from an edited text — E7-T03's seed sentence becomes the current seed's, a club's own use goes with its space or
     * brackets — and the rest stays. The correction is N-08b's only.
     */
    @Test void E7_T06_n08bLosesClassDescriptionAndKeepsTheRest() {
        var current = MessageTemplateSeed.load().of("N-08b").orElseThrow().body();
        assertThat(TemplateUpgrade.withoutClassDescription("[[class_date]] · {class_description}, amb [[dog_name]]: la classe ha canviat. [[changes]]. Mira-ho a l'app."))
                .isEqualTo(current.get("ca"));
        assertThat(TemplateUpgrade.withoutClassDescription("[[class_date]] · {class_description}, con [[dog_name]]: la clase ha cambiado. [[changes]]. Consulta la app."))
                .isEqualTo(current.get("es"));
        assertThat(TemplateUpgrade.withoutClassDescription("[[class_date]] · {class_description}, with [[dog_name]]: the class has changed. [[changes]]. Check the app."))
                .isEqualTo(current.get("en"));
        assertThat(TemplateUpgrade.withoutClassDescription("Classe {class_description} del [[class_date]]: [[changes]].")).isEqualTo("Classe del [[class_date]]: [[changes]].");
        assertThat(TemplateUpgrade.withoutClassDescription("Classe del [[class_date]] ({ class_description }). Truca'ns.")).isEqualTo("Classe del [[class_date]]. Truca'ns.");
        assertThat(TemplateUpgrade.withoutClassDescription("Sense descripció: [[changes]].")).isEqualTo("Sense descripció: [[changes]].");
        assertThat(TemplateUpgrade.withoutClassDescription(null)).isNull();
        // Only N-08b's texts are corrected for it; N-02's link and every code's `gender` keys as before.
        assertThat(TemplateUpgrade.corrected("N-08b", "Classe {class_description}. {gender, select, FEMALE {a} other {o}}")).isEqualTo("Classe. {gender, select, female {a} other {o}}");
        assertThat(TemplateUpgrade.corrected("N-15", "Classe {class_description}.")).isEqualTo("Classe {class_description}.");
        assertThat(TemplateUpgrade.corrected("N-08b", "Entra: [[link]].")).isEqualTo("Entra: [[link]].");
    }

    @Test void E7_T06_whatTheUpgradeWritesForEachKindOfTemplate() {
        var e7t02 = template("N-02", TemplateKind.CATALOG, text("ca", "{gender, select, FEMALE {Benvinguda} other {Benvingut}}, {member_first_name}!", "es", "¡Hola!", "en", "Hi!"),
                text("ca", "La teva alta a {club_name} està validada. Entra al teu compte amb aquest enllaç.", "es", "Hola.", "en", "Hi."), false, TemplateStatus.ACTIVE);
        // Never edited: the current seed (E81), in the club's languages and the ones it stores (en kept); the rest as stored.
        var seeded = TemplateUpgrade.upgraded(e7t02, seed("N-02", "ca", "es", "en"));
        assertThat(seeded.title().values()).isEqualTo(seed("N-02", "ca", "es", "en").title().values()).containsOnlyKeys("ca", "es", "en");
        assertThat(seeded.body().values().get("ca")).isEqualTo("Ja tens accés a l'app del club.");
        assertThat(seeded.customized()).isFalse(); assertThat(seeded.version()).isZero(); assertThat(seeded.icon()).isEqualTo(TemplateIcon.mail);
        assertThat(TemplateUpgrade.upgraded(seeded, seed("N-02", "ca", "es", "en"))).isSameAs(seeded); // idempotent
        // Edited: every stored language corrected, none dropped (round 2, review #1): keys lowered, link sentence gone.
        var edited = template("N-02", TemplateKind.CATALOG, e7t02.title(), e7t02.body(), true, TemplateStatus.ACTIVE);
        var corrected = TemplateUpgrade.upgraded(edited, seed("N-02", "ca", "es", "en"));
        assertThat(corrected.title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Benvinguda} other {Benvingut}}, {member_first_name}!", "es", "¡Hola!", "en", "Hi!"));
        assertThat(corrected.body().values()).isEqualTo(Map.of("ca", "La teva alta a {club_name} està validada.", "es", "Hola.", "en", "Hi."));
        assertThat(corrected.customized()).isTrue();
        // An edited N-09 in a language the club removed: nothing to correct, nothing written, the English words stay.
        var n09 = template("N-09", TemplateKind.CATALOG, text("ca", "Nou nivell", "es", "Nuevo nivel", "en", "Our new level"), text("ca", "[[dog_name]]: [[level_name]].",
                "es", "[[dog_name]]: [[level_name]].", "en", "[[dog_name]] moves up to [[level_name]]!"), true, TemplateStatus.ACTIVE);
        assertThat(TemplateUpgrade.upgraded(n09, seed("N-09", "ca", "es", "en"))).isSameAs(n09);
        // An edited body that was the link sentence only takes the seed's text of that language (a body is required).
        var onlyLink = template("N-02", TemplateKind.CATALOG, text("ca", "Hola!"), text("ca", "Entra-hi amb aquest enllaç: [[link]]."), true, TemplateStatus.ACTIVE);
        assertThat(TemplateUpgrade.upgraded(onlyLink, seed("N-02")).body().values()).isEqualTo(Map.of("ca", "Ja tens accés a l'app del club."));
        // A blank result in a language the product has no seed for keeps the text as it was.
        var foreign = template("N-02", TemplateKind.CATALOG, text("ca", "Hola!", "fr", "Salut!"), text("ca", "Hola.", "fr", "Entra-hi amb aquest enllaç: [[link]]."), true,
                TemplateStatus.ACTIVE);
        assertThat(TemplateUpgrade.upgraded(foreign, seed("N-02")).body().values()).isEqualTo(Map.of("ca", "Hola.", "fr", "Entra-hi amb aquest enllaç: [[link]]."));
        // Edited only by the old upper-case keys: once lowered it is the seed again, customized false.
        var seed02 = seed("N-02");
        var upperCase = template("N-02", TemplateKind.CATALOG, new LocalizedText(Map.of("ca", seed02.title().values().get("ca").replace("female", "FEMALE"),
                "es", seed02.title().values().get("es")), "ca"), seed02.body(), true, TemplateStatus.ACTIVE);
        assertThat(upperCase.title().values().get("ca")).contains("FEMALE");
        assertThat(TemplateUpgrade.upgraded(upperCase, seed02).customized()).isFalse();
        // An edited N-08b that kept E7-T03's `{class_description}`: the argument goes, the club's words and SMS stay.
        var n08b = template("N-08b", TemplateKind.CATALOG, seed("N-08b").title(), text("ca", "[[class_date]] · {class_description}, amb [[dog_name]]: canvis. [[changes]].",
                "es", seed("N-08b").body().values().get("es")), seed("N-08b").smsBody(), true, TemplateStatus.ACTIVE);
        var n08bCorrected = TemplateUpgrade.upgraded(n08b, seed("N-08b"));
        assertThat(n08bCorrected.body().values()).isEqualTo(Map.of("ca", "[[class_date]], amb [[dog_name]]: canvis. [[changes]].", "es", seed("N-08b").body().values().get("es")));
        assertThat(n08bCorrected.smsBody()).isEqualTo(n08b.smsBody()); assertThat(n08bCorrected.customized()).isTrue();
        // CUSTOM: the keys lowered, nothing else; an up-to-date one, an archived one, a code without seed: untouched.
        var custom = template(null, TemplateKind.CUSTOM, text("ca", "{gender, select, FEMALE {Estimada} other {Estimat}}"), text("ca", "Sopar."), false, TemplateStatus.ACTIVE);
        assertThat(TemplateUpgrade.upgraded(custom, null).title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Estimada} other {Estimat}}"));
        var current = template(null, TemplateKind.CUSTOM, text("ca", "Hola"), text("ca", "Sopar."), false, TemplateStatus.ACTIVE);
        assertThat(TemplateUpgrade.upgraded(current, null)).isSameAs(current);
        var archived = template(null, TemplateKind.CUSTOM, custom.title(), custom.body(), false, TemplateStatus.ARCHIVED);
        assertThat(TemplateUpgrade.upgraded(archived, null)).isSameAs(archived);
        assertThat(TemplateUpgrade.upgraded(e7t02, null)).isSameAs(e7t02);
    }

    /**
     * Round 2 (review #2, ruling E81): one club never stops the start-up — a club whose configuration fails and a club whose
     * templates cannot be read are logged and counted, the other clubs are upgraded, and the start-up hook returns; even a
     * failure to list the clubs is only logged. Round 2 (review #4): every write publishes `MessageTemplateChanged` and audits
     * `CATALOG_CHANGED` by `system:template-upgrade`; nothing when nothing changes.
     */
    @Test void E7_T06_aConcurrentSaveAFailedWriteAFailingClubAndACommandNeverStopTheStartUp() {
        var repository = mock(MessageTemplateRepository.class); var configs = mock(ClubConfigService.class);
        var events = mock(EventPublisher.class); var audit = mock(AuditWriter.class);
        var provider = new TemplateProvider(repository, MessageTemplateSeed.load(), CLOCK, null);
        var e7t02 = template("N-02", TemplateKind.CATALOG, text("ca", "{gender, select, FEMALE {Benvinguda} other {Benvingut}}!"), text("ca", "Validada. Entra al teu compte amb aquest enllaç."),
                false, TemplateStatus.ACTIVE);
        // A D9 save between the read and the write: STALE_VERSION, read again (now edited), corrected from there.
        var saved = new MessageTemplate(e7t02.id(), "club", "N-02", TemplateKind.CATALOG, e7t02.category(), e7t02.title(), text("ca", "Ja ets dels nostres. Entra al teu compte amb aquest enllaç."),
                null, e7t02.icon(), e7t02.color(), Map.of(), true, true, true, TemplateStatus.ACTIVE, 1L, Instant.EPOCH, "seed", Instant.EPOCH, "admin");
        var broken = template("N-09", TemplateKind.CATALOG, text("ca", "x"), text("ca", "y"), false, TemplateStatus.ACTIVE);
        // Sorted club ids: the two failing clubs come first, the deleted one between, and the good one last.
        when(repository.clubIds()).thenReturn(List.of("a-broken-config", "b-broken-templates", "c-gone", "club"));
        when(configs.get("a-broken-config")).thenThrow(new IllegalStateException("unreadable club"));
        when(configs.get("b-broken-templates")).thenReturn(CA_ES);
        when(configs.get("c-gone")).thenThrow(new ApiException(ErrorCode.CLUB_NOT_FOUND));
        when(configs.get("club")).thenReturn(CA_ES);
        when(repository.findAll()).thenAnswer(call -> {
            if ("b-broken-templates".equals(TenantContext.current())) { throw new IllegalArgumentException("No enum constant TemplateKind.BOGUS"); }
            return List.of(e7t02, broken);
        });
        when(repository.findById(e7t02.id())).thenReturn(Optional.of(saved));
        when(repository.update(any(), anyLong())).thenAnswer(call -> {
            MessageTemplate next = call.getArgument(0); long version = call.getArgument(1);
            if (next.code().equals("N-09")) { throw new IllegalStateException("write failed"); }
            if (version == 0L) { throw new ApiException(ErrorCode.STALE_VERSION); }
            return new MessageTemplate(next.id(), next.clubId(), next.code(), next.kind(), next.category(), next.title(), next.body(), next.smsBody(), next.icon(),
                    next.color(), next.matrix(), next.enabled(), next.mandatory(), next.customized(), next.status(), version + 1, next.createdAt(), next.createdBy(),
                    next.updatedAt(), next.updatedBy());
        });
        var upgrade = new TemplateUpgrade(repository, provider, configs, transactions(), events, audit, CLOCK, "");
        assertThat(upgrade.upgradeAll()).isEqualTo(new TemplateUpgrade.Result(0, 1, 1, 2));
        var written = ArgumentCaptor.forClass(MessageTemplate.class);
        verify(repository, times(1)).update(written.capture(), eq(1L));
        assertThat(written.getValue().body().values()).isEqualTo(Map.of("ca", "Ja ets dels nostres."));
        assertThat(written.getValue().updatedBy()).isEqualTo("system:template-upgrade"); assertThat(written.getValue().updatedAt()).isEqualTo(CLOCK.instant());
        // The one write: its event and its audit entry, by the upgrade; the refused and the failed writes have neither.
        var event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, times(1)).publish(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(MessagingEvent.class, e -> {
            assertThat(e.kind()).isEqualTo(MessagingEvent.Kind.MessageTemplateChanged); assertThat(e.aggregateId()).isEqualTo(e7t02.id());
            assertThat(e.actorAccountId()).isEqualTo("system:template-upgrade"); assertThat(e.origin()).isEqualTo(DomainEvent.Origin.SYSTEM);
            assertThat(e.payload()).containsEntry("id", e7t02.id()).extractingByKey("diff").asInstanceOf(InstanceOfAssertFactories.MAP).containsOnlyKeys("title", "body");
        });
        var entry = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit, times(1)).writeAsSystem(entry.capture(), eq("system:template-upgrade"));
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.CATALOG_CHANGED); assertThat(entry.getValue().entityId()).isEqualTo(e7t02.id());
        // The start-up hook never throws: a failing club, and even the clubs that cannot be listed, only log.
        upgrade.afterSingletonsInstantiated();
        when(repository.clubIds()).thenThrow(new IllegalStateException("database down"));
        assertThatCode(upgrade::afterSingletonsInstantiated).doesNotThrowAnyException();
        // A save that keeps answering STALE_VERSION gives up after three reads (one failure); one deleted meanwhile is nothing.
        reset(repository, events, audit);
        when(repository.findAll()).thenReturn(List.of(e7t02));
        when(repository.findById(e7t02.id())).thenReturn(Optional.of(e7t02), Optional.of(e7t02), Optional.empty());
        when(repository.update(any(), anyLong())).thenThrow(new ApiException(ErrorCode.STALE_VERSION));
        try (var tenant = TenantContext.open("club")) { assertThat(upgrade.upgrade(CA_ES)).isEqualTo(new TemplateUpgrade.Result(0, 0, 1, 0)); }
        when(repository.findById(e7t02.id())).thenReturn(Optional.empty());
        try (var tenant = TenantContext.open("club")) { assertThat(upgrade.upgrade(CA_ES)).isEqualTo(new TemplateUpgrade.Result(0, 0, 0, 0)); }
        verifyNoInteractions(events, audit);
        // A `bin/core` command never reads anything.
        var command = mock(MessageTemplateRepository.class);
        new TemplateUpgrade(command, provider, configs, transactions(), events, audit, CLOCK, "club:apply").afterSingletonsInstantiated();
        verifyNoInteractions(command);
    }

    /**
     * Round 2 (review #1, ruling E81): in a `ca/es` club, an N-09 the club edited in `ca/es/en` (before it removed `en`) and an
     * up-to-date never-edited one have nothing to correct: nothing is written, published or audited, so the English words stay.
     * Before the fix the upgrade wrote the club's `ca/es` view of the edited one.
     */
    @Test void E7_T06_nothingToCorrectIsNeverWrittenAndALanguageTheClubRemovedStays() {
        var repository = mock(MessageTemplateRepository.class); var events = mock(EventPublisher.class); var audit = mock(AuditWriter.class);
        var upgrade = new TemplateUpgrade(repository, new TemplateProvider(repository, MessageTemplateSeed.load(), CLOCK, null), mock(ClubConfigService.class),
                transactions(), events, audit, CLOCK, "");
        var current = template("N-09", TemplateKind.CATALOG, seed("N-09").title(), seed("N-09").body(), false, TemplateStatus.ACTIVE);
        var edited = new MessageTemplate("t-N-09-edited", "club", "N-09", TemplateKind.CATALOG, current.category(), text("ca", "Nou nivell", "es", "Nuevo nivel", "en", "Our new level"),
                text("ca", "[[dog_name]]: [[level_name]].", "es", "[[dog_name]]: [[level_name]].", "en", "[[dog_name]] moves up to [[level_name]]!"), null, TemplateIcon.up,
                TemplateColor.OK, Map.of(), true, false, true, TemplateStatus.ACTIVE, 3L, Instant.EPOCH, "seed", Instant.EPOCH, "account-admin");
        when(repository.findAll()).thenReturn(List.of(current, edited));
        try (var tenant = TenantContext.open("club")) { assertThat(upgrade.upgrade(CA_ES)).isEqualTo(new TemplateUpgrade.Result(0, 0, 0, 0)); }
        verify(repository, never()).update(any(), anyLong()); verifyNoInteractions(events, audit);
    }

    /**
     * Step 5 (claude review #3): `seedArguments` — what a save accepts besides the row's variables — is the seed's `select` and
     * `plural` argument names only. Before the fix it was every ICU argument of the seed: N-08b's `class_description` too, a
     * hidden second variable list. N-32c's `date` is a row variable since round 2 (P1, ruling E81), never a seed argument.
     */
    @Test void E7_T06_seedArgumentsAreTheSeedsSelectorsOnly() {
        var provider = new TemplateProvider(null, MessageTemplateSeed.load(), CLOCK, null);
        var service = new MessageTemplateService(null, provider, null, null, null, null, null, CLOCK, null);
        assertThat(service.seedArguments("N-01")).containsExactly("has_upfront");
        assertThat(service.seedArguments("N-15")).containsExactly("mode");
        assertThat(service.seedArguments("N-16")).containsExactly("auto_cancel");
        assertThat(service.seedArguments("N-08b")).isEmpty(); assertThat(service.seedArguments("N-32c")).isEmpty();
        assertThat(service.rules(TemplateKind.CATALOG, "N-08b", NotificationCategory.CLUB_CHANGES).icuNames()).doesNotContain("class_description");
        assertThat(service.rules(TemplateKind.CATALOG, "N-32c", NotificationCategory.CLUB_CHANGES).variables()).contains("date");
        assertThat(service.seedArguments("N-99")).isEmpty();
    }
}
