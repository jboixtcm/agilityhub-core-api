package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.TemplateUpgrade;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * E7-T06 step 1 (E7-T03's round-2 reviews: codex #1, claude #1): the templates a database stored before E7-T03's round 2 are
 * brought up to date by the start-up upgrade ({@link TemplateUpgrade}), with the literal texts those versions stored:
 * E7-T02's first use of N-02 and N-15 (the `messages_*` copy of `ca83d97^`: ICU `{var}`, `FEMALE` keys, every product language)
 * and E7-T03's round-1 N-02 (`ca83d97`'s seed, «Entra-hi amb aquest enllaç: [[link]].»). Each test first shows the defect on
 * the stored template (a woman greeted «Benvingut», the empty link, the round-1 PUT refused), runs the start-up hook, then:
 * D9's GET and an unchanged PUT answer 200, a delivery to a `FEMALE` member reads «Benvinguda», no N-02 delivery has a link
 * sentence, and an edited template keeps the club's words. The club (`e7t02-engine`) is a `ca/es` club.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TemplateUpgradeIT extends EngineFixtures {
    /** E7-T02's N-02, `messages_{ca,es,en}.properties` of `ca83d97^` (`notif.N-02.title|body`). */
    static final Map<String, String> E7T02_N02_TITLE = texts("{gender, select, FEMALE {Benvinguda} other {Benvingut}}, {member_first_name}!",
            "{gender, select, FEMALE {¡Bienvenida} other {¡Bienvenido}}, {member_first_name}!", "{gender, select, FEMALE {Welcome} other {Welcome}}, {member_first_name}!");
    static final Map<String, String> E7T02_N02_BODY = texts("La teva alta a {club_name} està validada. Entra al teu compte amb aquest enllaç.",
            "Tu alta en {club_name} está validada. Accede a tu cuenta con este enlace.", "Your membership at {club_name} is active. Use the link to access your account.");
    /** E7-T02's N-15 (`notif.N-15.title|body|sms` of `ca83d97^`). */
    static final Map<String, String> E7T02_N15_TITLE = texts("S'ha alliberat una plaça!", "¡Se ha liberado una plaza!", "A place has been freed up!");
    static final Map<String, String> E7T02_N15_BODY = texts(
            "Classe {class_description} · {class_date} · {class_time}, amb {dog_name}. {mode, select, FIFO {La plaça és per a tu fins a les {confirm_by}: toca «Agafa la plaça» per confirmar-la.} other {Estàs a la llista d'espera — la plaça és per a qui confirmi primer.}}",
            "Clase {class_description} · {class_date} · {class_time}, con {dog_name}. {mode, select, FIFO {La plaza es para ti hasta las {confirm_by}: toca «Coger la plaza» para confirmarla.} other {Estás en la lista de espera: la plaza es para quien la confirme primero.}}",
            "{class_description} class · {class_date} · {class_time}, with {dog_name}. {mode, select, FIFO {The place is yours until {confirm_by}: tap «Take the place» to confirm it.} other {You are on the waiting list: the place goes to whoever confirms first.}}");
    static final Map<String, String> E7T02_N15_SMS = texts(
            "{club_name}: plaça lliure a la classe {class_date} {class_time} ({class_description}). {mode, select, FIFO {Confirma-la a l'app abans de les {confirm_by}.} other {Entra a l'app per agafar-la.}}",
            "{club_name}: plaza libre en la clase {class_date} {class_time} ({class_description}). {mode, select, FIFO {Confírmala en la app antes de las {confirm_by}.} other {Entra en la app para cogerla.}}",
            "{club_name}: a place is free in the class {class_date} {class_time} ({class_description}). {mode, select, FIFO {Confirm it in the app before {confirm_by}.} other {Take it in the app.}}");
    /** E7-T03's round-1 N-02 (`seed/message-templates.*.json` of `ca83d97`). */
    static final Map<String, String> ROUND1_N02_TITLE = texts("{gender, select, female {Benvinguda} other {Benvingut}} a [[club_name]], [[member_first_name]]!",
            "{gender, select, female {¡Bienvenida} other {¡Bienvenido}} a [[club_name]], [[member_first_name]]!", "Welcome to [[club_name]], [[member_first_name]]!");
    static final Map<String, String> ROUND1_N02_BODY = texts("Ja tens accés a l'app del club. Entra-hi amb aquest enllaç: [[link]].",
            "Ya tienes acceso a la app del club. Entra con este enlace: [[link]].", "You now have access to the club's app. Sign in with this link: [[link]].");

    @Autowired MockMvc mvc; @Autowired TemplateUpgrade upgrade; @Autowired HostTenantResolver hosts;

    static Map<String, String> texts(String ca, String es, String en) { var values = new LinkedHashMap<String, String>(); values.put("ca", ca); values.put("es", es); values.put("en", en); return values; }

    @BeforeEach void caEsClub() {
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow());
        tree.set("locales", mapper.valueToTree(List.of("ca", "es"))); tree.put("defaultLocale", "ca");
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB); hosts.invalidate();
    }

    /** A template as an earlier version stored it: never edited (`customized = false`) or edited at D9. */
    MessageTemplate stored(String clubId, String code, Map<String, String> title, Map<String, String> body, Map<String, String> sms, boolean customized) {
        var spec = NotificationCatalog.byCode(code).orElseThrow();
        var matrix = MessageTemplateSeed.load().of(code).orElseThrow().matrix();
        String by = customized ? "account-admin" : "system:notification-engine";
        return mongo.insert(new MessageTemplate(null, clubId, code, TemplateKind.CATALOG, spec.category(), new LocalizedText(title, "ca"), new LocalizedText(body, "ca"),
                sms == null ? null : new LocalizedText(sms, "ca"), spec.icon(), spec.color(), matrix, true, spec.mandatory(), customized, TemplateStatus.ACTIVE, null,
                clock.instant(), "system:notification-engine", clock.instant(), by));
    }
    MessageTemplate reread(MessageTemplate template) { return mongo.findById(template.id(), MessageTemplate.class); }
    MessageTemplate seed(String clubId, String code, List<String> locales) {
        try (var tenant = TenantContext.open(clubId)) { return templates.seed(NotificationCatalog.byCode(code).orElseThrow(), locales, "ca"); }
    }
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request, String clubId) {
        return request.header("Host", clubId.equals(CLUB) ? HOST : "ba." + HOST).contentType("application/json")
                .with(jwt().jwt(j -> j.subject("account-admin").claim("clubId", clubId)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
    JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    /** D9's GET, then the PUT of exactly what it showed. */
    JsonNode getThenPutUnchanged(String clubId, MessageTemplate template, int status) throws Exception {
        var detail = call(admin(get("/api/v1/message-templates/" + template.id()), clubId), 200);
        var body = mapper.createObjectNode();
        body.set("title", detail.path("titleI18n")); body.set("body", detail.path("bodyI18n")); body.set("smsBody", detail.path("smsBodyI18n"));
        body.put("icon", detail.path("icon").asText()); body.put("color", detail.path("color").asText()); body.set("matrix", detail.path("matrix"));
        body.put("enabled", detail.path("enabled").asBoolean()); body.put("version", detail.path("version").asLong());
        return call(admin(put("/api/v1/message-templates/" + template.id()).content(body.toString()), clubId), status);
    }
    /** The N-02 of a `MemberValidated` of `memberId` (the APP copy, EngineFixtures' in-memory directory). */
    Notification welcome(String clubId, String memberId) {
        mongo.remove(Query.query(Criteria.where("clubId").is(clubId).and("code").is("N-02")), Notification.class);
        deliver(clubId, "MemberValidated", Map.of("memberId", memberId));
        return stored(clubId, "N-02").stream().filter(n -> n.audience() == NotificationAudience.MEMBER).findFirst().orElseThrow();
    }
    static void noLinkSentence(Notification notice) {
        assertThat(notice.title() + " " + notice.body()).as(notice.locale()).doesNotContain("enllaç", "enlace", "link", ": .", "[[", "{", "}");
    }

    /**
     * Never edited (`customized = false`): E7-T02's N-02 and N-15 and E7-T03's round-1 N-02 take the current seed in the
     * club's languages (`ca/es`; the Buenos Aires club keeps its three). Before the upgrade Laura (`FEMALE`) read «Benvingut»,
     * E7-T02's N-02 kept its link sentence, round 1's rendered «…enllaç: .», and round 1's unchanged PUT was `400
     * TEMPLATE_UNKNOWN_VARIABLE`. The run is idempotent, and a `bin/core` command never runs it.
     */
    @Test void E7_T06_neverEditedTemplatesOfE7T02AndOfRound1TakeTheCurrentSeed() throws Exception {
        var n02 = stored(CLUB, "N-02", E7T02_N02_TITLE, E7T02_N02_BODY, null, false);
        var n15 = stored(CLUB, "N-15", E7T02_N15_TITLE, E7T02_N15_BODY, E7T02_N15_SMS, false);
        var round1 = stored(OTHER, "N-02", ROUND1_N02_TITLE, ROUND1_N02_BODY, null, false);
        // The defects, on the stored texts.
        var before = welcome(CLUB, "member-laura");
        assertThat(before.title()).isEqualTo("Benvingut, Laura!"); assertThat(before.body()).contains("Entra al teu compte amb aquest enllaç.");
        assertThat(welcome(OTHER, "member-laura").body()).isEqualTo("Ja tens accés a l'app del club. Entra-hi amb aquest enllaç: .");
        assertThat(getThenPutUnchanged(OTHER, round1, 400).path("code").asText()).isEqualTo("TEMPLATE_UNKNOWN_VARIABLE");
        // A `bin/core` command (club:apply --dry-run among them) never upgrades.
        new TemplateUpgrade(templateRepository, templates, configs, clock, "club:apply").afterSingletonsInstantiated();
        assertThat(reread(n02).title().values()).isEqualTo(E7T02_N02_TITLE); assertThat(reread(n02).version()).isZero();

        upgrade.afterSingletonsInstantiated(); // the API's start-up

        var seed02 = seed(CLUB, "N-02", List.of("ca", "es"));
        assertThat(reread(n02)).satisfies(t -> {
            assertThat(t.title().values()).isEqualTo(seed02.title().values()).containsOnlyKeys("ca", "es");
            assertThat(t.body().values()).isEqualTo(Map.of("ca", "Ja tens accés a l'app del club.", "es", "Ya tienes acceso a la app del club."));
            assertThat(t.customized()).isFalse(); assertThat(t.version()).isEqualTo(1); assertThat(t.updatedBy()).isEqualTo("system:template-upgrade");
            assertThat(t.matrix()).isEqualTo(n02.matrix()); assertThat(t.icon()).isEqualTo(TemplateIcon.mail); assertThat(t.color()).isEqualTo(TemplateColor.OK);
        });
        var seed15 = seed(CLUB, "N-15", List.of("ca", "es"));
        assertThat(reread(n15)).satisfies(t -> {
            assertThat(t.body().values()).isEqualTo(seed15.body().values()); assertThat(t.smsBody().values()).isEqualTo(seed15.smsBody().values());
            assertThat(t.title().values()).containsOnlyKeys("ca", "es"); assertThat(t.body().values().get("ca")).contains("[[confirm_by]]").doesNotContain("{class_description}");
        });
        assertThat(reread(round1).body().values()).isEqualTo(seed(OTHER, "N-02", List.of("ca", "es", "en")).body().values());
        // D9: GET, then an unchanged PUT → 200 (N-02, N-15 and round 1's).
        for (var entry : List.of(Map.entry(CLUB, n02), Map.entry(CLUB, n15), Map.entry(OTHER, round1))) {
            var saved = getThenPutUnchanged(entry.getKey(), entry.getValue(), 200);
            assertThat(saved.path("customized").asBoolean()).as(entry.getValue().code()).isFalse(); assertThat(saved.path("version").asLong()).isEqualTo(2);
        }
        // Deliveries: Laura (FEMALE, ca) «Benvinguda», Marc (MALE, es) «Bienvenido», Anna (FEMALE, en → the club's ca), none with a link.
        var laura = welcome(CLUB, "member-laura");
        assertThat(laura.title()).isEqualTo("Benvinguda a Club Agility Exemple, Laura!"); assertThat(laura.body()).isEqualTo("Ja tens accés a l'app del club.");
        var marc = welcome(CLUB, "member-marc");
        assertThat(marc.title()).isEqualTo("¡Bienvenido a Club Agility Exemple, Marc!"); assertThat(marc.body()).isEqualTo("Ya tienes acceso a la app del club.");
        var anna = welcome(CLUB, "member-anna");
        assertThat(anna.title()).isEqualTo("Benvinguda a Club Agility Exemple, Anna!");
        var other = welcome(OTHER, "member-laura");
        assertThat(other.title()).isEqualTo("Benvinguda a Club Agility Sud, Laura!"); assertThat(other.body()).isEqualTo("Ja tens accés a l'app del club.");
        for (var notice : List.of(laura, marc, anna, other)) { noLinkSentence(notice); }
        // Idempotent: a second start-up writes nothing.
        long version = reread(n02).version();
        var again = upgrade.upgradeAll();
        assertThat(again.seeded() + again.corrected() + again.failed()).isZero(); assertThat(reread(n02).version()).isEqualTo(version);
        System.out.println("E7-T06 step 1, never edited: N-02 " + laura.title() + " | " + laura.body() + " · N-15 " + reread(n15).body().values().get("ca"));
    }

    /**
     * Edited at D9 (`customized = true`): the club's words stay, in the club's languages, with the `gender` keys in lower case
     * and N-02's link sentence removed — an E7-T02 N-02 whose club rewrote the body, a round-1 N-02 whose club added a
     * sentence, and a `CUSTOM` notice with `FEMALE` keys. Before the upgrade the club's N-02 greeted Laura «Benvingut» and kept
     * the link sentence, and round 1's could not be saved unchanged.
     */
    @Test void E7_T06_editedTemplatesKeepTheClubsWordsWithLowerCaseGenderKeysAndNoLinkSentence() throws Exception {
        var title = new LinkedHashMap<>(E7T02_N02_TITLE); title.put("ca", "{gender, select, FEMALE {Benvinguda} MALE {Benvingut} other {Benvingut/da}} al club, {member_first_name}!");
        var body = new LinkedHashMap<>(E7T02_N02_BODY); body.put("ca", "La teva alta a {club_name} ja és confirmada. Entra al teu compte amb aquest enllaç. Ens veiem a la pista!");
        var n02 = stored(CLUB, "N-02", title, body, null, true);
        var round1Body = new LinkedHashMap<>(ROUND1_N02_BODY); round1Body.put("ca", "Ja pots entrar a l'app del nostre club. Entra-hi amb aquest enllaç: [[link]]. Fins aviat!");
        var round1 = stored(OTHER, "N-02", ROUND1_N02_TITLE, round1Body, null, true);
        MessageTemplate custom;
        try (var tenant = TenantContext.open(CLUB)) {
            custom = mongo.insert(new MessageTemplate(null, CLUB, null, TemplateKind.CUSTOM, com.agilityhub.core.clubs.messaging.domain.NotificationCategory.CLUB_NEWS,
                    new LocalizedText(Map.of("ca", "{gender, select, FEMALE {Estimada} other {Estimat}} [[member_first_name]]"), "ca"),
                    new LocalizedText(Map.of("ca", "Dissabte hi ha sopar de socis a [[club_name]]."), "ca"), null, TemplateIcon.bell, TemplateColor.NEUTRAL, Map.of(), true,
                    false, false, TemplateStatus.ACTIVE, null, clock.instant(), "account-admin", clock.instant(), "account-admin"));
        }
        assertThat(welcome(CLUB, "member-laura").title()).isEqualTo("Benvingut/da al club, Laura!");
        assertThat(getThenPutUnchanged(OTHER, round1, 400).path("code").asText()).isEqualTo("TEMPLATE_UNKNOWN_VARIABLE");

        upgrade.afterSingletonsInstantiated();

        assertThat(reread(n02)).satisfies(t -> {
            assertThat(t.title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Benvinguda} male {Benvingut} other {Benvingut/da}} al club, {member_first_name}!",
                    "es", "{gender, select, female {¡Bienvenida} other {¡Bienvenido}}, {member_first_name}!"));
            assertThat(t.body().values()).isEqualTo(Map.of("ca", "La teva alta a {club_name} ja és confirmada. Ens veiem a la pista!", "es", "Tu alta en {club_name} está validada."));
            assertThat(t.customized()).isTrue(); assertThat(t.version()).isEqualTo(1);
        });
        assertThat(reread(round1).body().values()).isEqualTo(Map.of("ca", "Ja pots entrar a l'app del nostre club. Fins aviat!", "es", "Ya tienes acceso a la app del club.",
                "en", "You now have access to the club's app."));
        assertThat(reread(custom).title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Estimada} other {Estimat}} [[member_first_name]]"));
        assertThat(getThenPutUnchanged(CLUB, n02, 200).path("customized").asBoolean()).isTrue();
        assertThat(getThenPutUnchanged(OTHER, round1, 200).at("/bodyI18n/ca").asText()).isEqualTo("Ja pots entrar a l'app del nostre club. Fins aviat!");
        var laura = welcome(CLUB, "member-laura");
        assertThat(laura.title()).isEqualTo("Benvinguda al club, Laura!"); assertThat(laura.body()).isEqualTo("La teva alta a Club Agility Exemple ja és confirmada. Ens veiem a la pista!");
        var marc = welcome(CLUB, "member-marc");
        assertThat(marc.title()).isEqualTo("¡Bienvenido, Marc!"); assertThat(marc.body()).isEqualTo("Tu alta en Club Agility Exemple está validada.");
        var other = welcome(OTHER, "member-laura");
        assertThat(other.body()).isEqualTo("Ja pots entrar a l'app del nostre club. Fins aviat!");
        for (var notice : List.of(laura, marc, other)) { noLinkSentence(notice); }
        assertThat(upgrade.upgradeAll().corrected()).isZero();
        System.out.println("E7-T06 step 1, edited: " + laura.title() + " | " + laura.body() + " · round 1: " + other.body());
    }
}
