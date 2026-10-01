package com.agilityhub.core.clubs.messaging.application.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agilityhub.core.clubs.messaging.application.MessagingTransactions;
import com.agilityhub.core.clubs.messaging.application.TemplateUpgrade;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * E7-T06 step 1 (E7-T03's round-2 reviews: codex #1, claude #1) and its round 2 (ruling E81): the templates a database stored
 * before E7-T03's round 2 are brought up to date by the start-up upgrade ({@link TemplateUpgrade}), with the literal texts
 * those versions stored: E7-T02's first use of N-02 and N-15 (the `messages_*` copy of `ca83d97^`: ICU `{var}`, `FEMALE` keys,
 * every product language), E7-T03's round-1 N-02 (`ca83d97`'s seed, «Entra-hi amb aquest enllaç: [[link]].») and E7-T03's
 * N-08b (`d46818b^`'s seed, «[[class_date]] · {class_description}, amb…»). Each test first shows the defect on the stored
 * template (a woman greeted «Benvingut», the empty link, the PUT refused), runs the start-up hook, then: D9's GET and an
 * unchanged PUT answer 200, a delivery to a `FEMALE` member reads «Benvinguda», no N-02 delivery has a link sentence, an
 * edited template keeps the club's words in every language it stores (also one the club removed), each change is a
 * `MessageTemplateChanged` and an audit entry of a system process (ruling E83: no account, the process in `details.job`;
 * nothing when nothing changes), and a club that fails never stops the others nor the start-up. The club (`e7t02-engine`)
 * is a `ca/es` club.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TemplateUpgradeIT extends EngineFixtures {
    /** The upgraded template's `updatedBy`. */
    static final String ACTOR = "system:template-upgrade";
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
    /** E7-T03's N-08b body (`seed/message-templates.*.json` of `d46818b^`), with `{class_description}` outside its row. */
    static final Map<String, String> E7T03_N08B_BODY = texts("[[class_date]] · {class_description}, amb [[dog_name]]: la classe ha canviat. [[changes]]. Mira-ho a l'app.",
            "[[class_date]] · {class_description}, con [[dog_name]]: la clase ha cambiado. [[changes]]. Consulta la app.",
            "[[class_date]] · {class_description}, with [[dog_name]]: the class has changed. [[changes]]. Check the app.");
    /** E7-T03's N-32c SMS (`d46818b^`): `{date}`, a row variable of N-32c since round 2 (P1, ruling E81). */
    static final Map<String, String> E7T03_N32C_SMS = texts("[[club_name]]: l’activitat [[activity_title]] ({date}) queda cancel·lada. [[admin_text]]",
            "[[club_name]]: la actividad [[activity_title]] ({date}) queda cancelada. [[admin_text]]", "[[club_name]]: [[activity_title]] ({date}) is cancelled. [[admin_text]]");

    @Autowired MockMvc mvc; @Autowired TemplateUpgrade upgrade; @Autowired HostTenantResolver hosts; @Autowired MessagingTransactions messagingTransactions;
    @Autowired AuditWriter audit;

    static Map<String, String> texts(String ca, String es, String en) { var values = new LinkedHashMap<String, String>(); values.put("ca", ca); values.put("es", es); values.put("en", en); return values; }
    static Map<String, String> only(Map<String, String> values, String... locales) {
        var kept = new LinkedHashMap<String, String>(); for (String locale : locales) { kept.put(locale, values.get(locale)); } return kept;
    }

    @BeforeEach void caEsClub() { locales(CLUB, "ca", "es"); }
    void locales(String clubId, String... locales) {
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(clubId).orElseThrow());
        tree.set("locales", mapper.valueToTree(List.of(locales))); tree.put("defaultLocale", "ca");
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId); hosts.invalidate();
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
                .with(jwt().jwt(j -> j.subject("account-admin").claim("clubId", clubId).claim("name", "Example Admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
    JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    JsonNode detail(String clubId, MessageTemplate template) throws Exception { return call(admin(get("/api/v1/message-templates/" + template.id()), clubId), 200); }
    /** D9's GET, then the PUT of exactly what it showed. */
    JsonNode getThenPutUnchanged(String clubId, MessageTemplate template, int status) throws Exception { return getThenPut(clubId, template, null, null, status); }
    /** D9's GET, then the PUT of what it showed with these title and body texts (null: as shown). */
    JsonNode getThenPut(String clubId, MessageTemplate template, Map<String, String> title, Map<String, String> body, int status) throws Exception {
        var detail = detail(clubId, template);
        var request = mapper.createObjectNode();
        request.set("title", title == null ? detail.path("titleI18n") : mapper.valueToTree(title)); request.set("body", body == null ? detail.path("bodyI18n") : mapper.valueToTree(body));
        request.set("smsBody", detail.path("smsBodyI18n"));
        request.put("icon", detail.path("icon").asText()); request.put("color", detail.path("color").asText()); request.set("matrix", detail.path("matrix"));
        request.put("enabled", detail.path("enabled").asBoolean()); request.put("version", detail.path("version").asLong());
        return call(admin(put("/api/v1/message-templates/" + template.id()).content(request.toString()), clubId), status);
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
    /** The outbox's `MessageTemplateChanged` of one template. */
    List<Document> changes(MessageTemplate template) {
        return mongo.find(Query.query(Criteria.where("clubId").is(template.clubId()).and("type").is("MessageTemplateChanged").and("aggregateId").is(template.id())),
                Document.class, "domain_events");
    }
    /** The audit entries of one template. */
    List<Document> audits(MessageTemplate template) {
        return mongo.find(Query.query(Criteria.where("entityType").is("MessageTemplate").and("entityId").is(template.id())), Document.class, "audit_entries");
    }
    /**
     * One `MessageTemplateChanged` and one `CATALOG_CHANGED` by the upgrade, with the stored texts before and after, written as
     * a system process (ruling E83, E7-T04 round 3): the event without `actorAccountId`, origin `SYSTEM`; the entry without
     * account or `actorName`, role and origin `SYSTEM`, `details.job = template-upgrade`; D9's «last change» has no name.
     */
    void changedByTheUpgrade(String clubId, MessageTemplate template, String field) throws Exception {
        assertThat(changes(template)).as(template.code() + " event").singleElement().satisfies(event -> {
            assertThat(event.containsKey("actorAccountId") ? event.get("actorAccountId") : null).isNull(); assertThat(event.getString("origin")).isEqualTo("SYSTEM");
            var payload = event.get("payload", Document.class);
            assertThat(payload.getString("id")).isEqualTo(template.id());
            assertThat(payload.get("diff", Document.class).get(field, Document.class)).containsOnlyKeys("before", "after");
        });
        assertThat(audits(template)).as(template.code() + " audit").singleElement().satisfies(entry -> {
            assertThat(entry.getString("action")).isEqualTo("CATALOG_CHANGED"); assertThat(entry.get("actorName")).isNull(); assertThat(entry.get("actorAccountId")).isNull();
            assertThat(entry.get("details", Document.class)).isEqualTo(new Document("job", "template-upgrade"));
            assertThat(entry.getString("actorRole")).isEqualTo("SYSTEM"); assertThat(entry.getString("origin")).isEqualTo("SYSTEM"); assertThat(entry.getString("clubId")).isEqualTo(clubId);
        });
        var last = detail(clubId, template).path("lastChange");
        assertThat(last.hasNonNull("actorName")).as(last.toString()).isFalse(); assertThat(last.path("action").asText()).isEqualTo("CATALOG_CHANGED");
    }

    /**
     * Never edited (`customized = false`): E7-T02's N-02 and N-15 and E7-T03's round-1 N-02 take the current seed (E81), in
     * the club's languages and in the ones they store (E7-T02 stored `en` in this `ca/es` club: it is kept, round 2). Before
     * the upgrade Laura (`FEMALE`) read «Benvingut», E7-T02's N-02 kept its link sentence, round 1's rendered «…enllaç: .», and
     * round 1's unchanged PUT was `400 TEMPLATE_UNKNOWN_VARIABLE`. Each change is an event and an audit entry by the upgrade;
     * the run is idempotent, and a `bin/core` command never runs it.
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
        new TemplateUpgrade(templateRepository, templates, configs, messagingTransactions, events, audit, clock, "club:apply").afterSingletonsInstantiated();
        assertThat(reread(n02).title().values()).isEqualTo(E7T02_N02_TITLE); assertThat(reread(n02).version()).isZero(); assertThat(changes(n02)).isEmpty();

        upgrade.afterSingletonsInstantiated(); // the API's start-up

        var seed02 = seed(CLUB, "N-02", List.of("ca", "es", "en"));
        assertThat(reread(n02)).satisfies(t -> {
            assertThat(t.title().values()).isEqualTo(seed02.title().values()).containsOnlyKeys("ca", "es", "en");
            assertThat(t.body().values()).isEqualTo(Map.of("ca", "Ja tens accés a l'app del club.", "es", "Ya tienes acceso a la app del club.",
                    "en", "You now have access to the club's app."));
            assertThat(t.customized()).isFalse(); assertThat(t.version()).isEqualTo(1); assertThat(t.updatedBy()).isEqualTo(ACTOR);
            assertThat(t.matrix()).isEqualTo(n02.matrix()); assertThat(t.icon()).isEqualTo(TemplateIcon.mail); assertThat(t.color()).isEqualTo(TemplateColor.OK);
        });
        var seed15 = seed(CLUB, "N-15", List.of("ca", "es", "en"));
        assertThat(reread(n15)).satisfies(t -> {
            assertThat(t.body().values()).isEqualTo(seed15.body().values()); assertThat(t.smsBody().values()).isEqualTo(seed15.smsBody().values());
            assertThat(t.title().values()).containsOnlyKeys("ca", "es", "en"); assertThat(t.body().values().get("ca")).contains("[[confirm_by]]").doesNotContain("{class_description}");
        });
        assertThat(reread(round1).body().values()).isEqualTo(seed(OTHER, "N-02", List.of("ca", "es", "en")).body().values());
        // Round 2 (review #4): each change is a MessageTemplateChanged and a CATALOG_CHANGED by the upgrade.
        changedByTheUpgrade(CLUB, n02, "body"); changedByTheUpgrade(CLUB, n15, "smsBody"); changedByTheUpgrade(OTHER, round1, "body");
        // D9: GET (the club's languages), then an unchanged PUT → 200 (N-02, N-15 and round 1's).
        for (var entry : List.of(Map.entry(CLUB, n02), Map.entry(CLUB, n15), Map.entry(OTHER, round1))) {
            var saved = getThenPutUnchanged(entry.getKey(), entry.getValue(), 200);
            assertThat(saved.path("customized").asBoolean()).as(entry.getValue().code()).isFalse(); assertThat(saved.path("version").asLong()).isEqualTo(2);
        }
        // Deliveries: Laura (FEMALE, ca) «Benvinguda», Marc (MALE, es) «Bienvenido», Anna (FEMALE, en) the club's ca since D9's save, none with a link.
        var laura = welcome(CLUB, "member-laura");
        assertThat(laura.title()).isEqualTo("Benvinguda a Club Agility Exemple, Laura!"); assertThat(laura.body()).isEqualTo("Ja tens accés a l'app del club.");
        var marc = welcome(CLUB, "member-marc");
        assertThat(marc.title()).isEqualTo("¡Bienvenido a Club Agility Exemple, Marc!"); assertThat(marc.body()).isEqualTo("Ya tienes acceso a la app del club.");
        var anna = welcome(CLUB, "member-anna");
        assertThat(anna.title()).isEqualTo("Benvinguda a Club Agility Exemple, Anna!");
        var other = welcome(OTHER, "member-laura");
        assertThat(other.title()).isEqualTo("Benvinguda a Club Agility Sud, Laura!"); assertThat(other.body()).isEqualTo("Ja tens accés a l'app del club.");
        for (var notice : List.of(laura, marc, anna, other)) { noLinkSentence(notice); }
        // Idempotent: a second start-up writes, publishes and audits nothing.
        long version = reread(n02).version();
        var again = upgrade.upgradeAll();
        assertThat(again.seeded() + again.corrected() + again.failed()).isZero(); assertThat(reread(n02).version()).isEqualTo(version);
        assertThat(changes(n02)).hasSize(2); // the upgrade's and the D9 save's, nothing more
        System.out.println("E7-T06 step 1, never edited: N-02 " + laura.title() + " | " + laura.body() + " · N-15 " + reread(n15).body().values().get("ca"));
    }

    /**
     * Edited at D9 (`customized = true`): the club's words stay, in every language they are stored in, with only the
     * corrections — the `gender` keys in lower case and N-02's link sentence removed — for an E7-T02 N-02 whose club rewrote the
     * body, a round-1 N-02 whose club added a sentence, and a `CUSTOM` notice with `FEMALE` keys. Before the upgrade the club's
     * N-02 greeted Laura «Benvingut/da» and kept the link sentence, and round 1's could not be saved unchanged. Round 2 (review
     * #1): the `en` texts E7-T02 stored in this `ca/es` club are corrected, not dropped.
     */
    @Test void E7_T06_editedTemplatesKeepTheClubsWordsWithLowerCaseGenderKeysAndNoLinkSentence() throws Exception {
        var title = new LinkedHashMap<>(E7T02_N02_TITLE); title.put("ca", "{gender, select, FEMALE {Benvinguda} MALE {Benvingut} other {Benvingut/da}} al club, {member_first_name}!");
        var body = new LinkedHashMap<>(E7T02_N02_BODY); body.put("ca", "La teva alta a {club_name} ja és confirmada. Entra al teu compte amb aquest enllaç. Ens veiem a la pista!");
        var n02 = stored(CLUB, "N-02", title, body, null, true);
        var round1Body = new LinkedHashMap<>(ROUND1_N02_BODY); round1Body.put("ca", "Ja pots entrar a l'app del nostre club. Entra-hi amb aquest enllaç: [[link]]. Fins aviat!");
        var round1 = stored(OTHER, "N-02", ROUND1_N02_TITLE, round1Body, null, true);
        MessageTemplate custom;
        try (var tenant = TenantContext.open(CLUB)) {
            custom = mongo.insert(new MessageTemplate(null, CLUB, null, TemplateKind.CUSTOM, NotificationCategory.CLUB_NEWS,
                    new LocalizedText(Map.of("ca", "{gender, select, FEMALE {Estimada} other {Estimat}} [[member_first_name]]"), "ca"),
                    new LocalizedText(Map.of("ca", "Dissabte hi ha sopar de socis a [[club_name]]."), "ca"), null, TemplateIcon.bell, TemplateColor.NEUTRAL, Map.of(), true,
                    false, false, TemplateStatus.ACTIVE, null, clock.instant(), "account-admin", clock.instant(), "account-admin"));
        }
        assertThat(welcome(CLUB, "member-laura").title()).isEqualTo("Benvingut/da al club, Laura!");
        assertThat(getThenPutUnchanged(OTHER, round1, 400).path("code").asText()).isEqualTo("TEMPLATE_UNKNOWN_VARIABLE");

        upgrade.afterSingletonsInstantiated();

        assertThat(reread(n02)).satisfies(t -> {
            assertThat(t.title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Benvinguda} male {Benvingut} other {Benvingut/da}} al club, {member_first_name}!",
                    "es", "{gender, select, female {¡Bienvenida} other {¡Bienvenido}}, {member_first_name}!", "en", "{gender, select, female {Welcome} other {Welcome}}, {member_first_name}!"));
            assertThat(t.body().values()).isEqualTo(Map.of("ca", "La teva alta a {club_name} ja és confirmada. Ens veiem a la pista!", "es", "Tu alta en {club_name} está validada.",
                    "en", "Your membership at {club_name} is active."));
            assertThat(t.customized()).isTrue(); assertThat(t.version()).isEqualTo(1);
        });
        assertThat(reread(round1).body().values()).isEqualTo(Map.of("ca", "Ja pots entrar a l'app del nostre club. Fins aviat!", "es", "Ya tienes acceso a la app del club.",
                "en", "You now have access to the club's app."));
        assertThat(reread(custom).title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Estimada} other {Estimat}} [[member_first_name]]"));
        changedByTheUpgrade(CLUB, n02, "title"); changedByTheUpgrade(OTHER, round1, "body"); changedByTheUpgrade(CLUB, custom, "title");
        assertThat(detail(CLUB, n02).path("bodyI18n").fieldNames()).toIterable().containsExactly("ca", "es"); // D9 shows the club's languages
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

    /**
     * E7-T06's round-2 review #1, fixed in E7-T04 round 3 (ruling E83; S11 §10, R-11-12). D9 stores the keys of a
     * `{gender, select, …}` in lower case, with the upgrade's own function. A PUT with `FEMALE`/`MALE` stores
     * `female`/`male`. The preview of the saved text, and of the same unsaved draft, renders the female form for the data
     * set's Laura, and so does Laura's notice. The next start-up has nothing to correct, so it writes, publishes and audits
     * nothing. Before the fix the PUT stored `FEMALE`, the preview and the notice read «Benvingut/da», and the next start-up
     * rewrote the club's text.
     */
    @Test void E7_T04_D9StoresGenderKeysInLowerCaseAndThePreviewRendersTheFemaleForm() throws Exception {
        MessageTemplate n02;
        try (var tenant = TenantContext.open(CLUB)) { n02 = templates.forCode(NotificationCatalog.byCode("N-02").orElseThrow(), List.of("ca", "es"), "ca"); }
        String upper = "{gender, select, FEMALE {Benvinguda} MALE {Benvingut} other {Benvingut/da}} a [[club_name]], [[member_first_name]]!";
        var title = new LinkedHashMap<String, String>();
        title.put("ca", upper); title.put("es", "{gender, select, FEMALE {¡Bienvenida} other {¡Bienvenido}} a [[club_name]], [[member_first_name]]!");
        getThenPut(CLUB, n02, title, null, 200);
        assertThat(reread(n02).title().values()).isEqualTo(Map.of("ca", "{gender, select, female {Benvinguda} male {Benvingut} other {Benvingut/da}} a [[club_name]], [[member_first_name]]!",
                "es", "{gender, select, female {¡Bienvenida} other {¡Bienvenido}} a [[club_name]], [[member_first_name]]!"));
        String welcomeLaura = "Benvinguda a Club Agility Exemple, Laura!";
        assertThat(call(admin(post("/api/v1/message-templates/" + n02.id() + "/preview").content("{\"locale\":\"ca\"}"), CLUB), 200).path("title").asText())
                .isEqualTo(welcomeLaura);
        var draft = mapper.createObjectNode(); draft.put("locale", "ca");
        draft.putObject("draft").put("title", upper).put("body", reread(n02).body().values().get("ca"));
        assertThat(call(admin(post("/api/v1/message-templates/" + n02.id() + "/preview").content(draft.toString()), CLUB), 200).path("title").asText())
                .isEqualTo(welcomeLaura);
        assertThat(welcome(CLUB, "member-laura").title()).isEqualTo(welcomeLaura);
        var saved = reread(n02); int events = changes(n02).size(), entries = audits(n02).size();
        upgrade.upgradeAll();
        assertThat(reread(n02).version()).isEqualTo(saved.version()); assertThat(reread(n02).title().values()).isEqualTo(saved.title().values());
        assertThat(changes(n02)).hasSize(events); assertThat(audits(n02)).hasSize(entries);
    }

    /**
     * Round 2, review #1 (ruling E81): the upgrade never prunes a language. A `ca/es/en` club edits N-09 in its three
     * languages at D9; its admin removes `en` from `club.locales`; the API restarts. Nothing is corrected, so nothing is
     * written, published or audited: the English words are still stored, D9 shows the `ca/es` texts, and adding `en` back shows
     * the English words again. Before the fix the upgrade wrote D9's `ca/es` view and the English words were gone for good.
     */
    @Test void E7_T06_aLanguageTheClubRemovedKeepsItsWordsAndNothingIsEmittedWhenNothingChanges() throws Exception {
        locales(CLUB, "ca", "es", "en");
        var seed09 = MessageTemplateSeed.load().of("N-09").orElseThrow();
        var n09 = stored(CLUB, "N-09", seed09.title(), seed09.body(), null, false);
        var title = texts("[[dog_name_article]] ja és de nivell [[level_name]]!", "¡[[dog_name_article]] ya es de nivel [[level_name]]!", "[[dog_name]] moves up to [[level_name]]!");
        var body = texts("Enhorabona! Les reserves ja fetes segueixen valent.", "¡Enhorabuena! Las reservas hechas siguen valiendo.", "Well done! Your bookings stay valid.");
        assertThat(getThenPut(CLUB, n09, title, body, 200).path("customized").asBoolean()).isTrue();
        var edited = reread(n09);
        assertThat(edited.body().values()).isEqualTo(body); long version = edited.version();
        int events = changes(n09).size(), entries = audits(n09).size();
        locales(CLUB, "ca", "es"); // the club removes `en`

        upgrade.afterSingletonsInstantiated(); // the API restarts

        assertThat(reread(n09)).satisfies(t -> {
            assertThat(t.title().values()).isEqualTo(title); assertThat(t.body().values()).isEqualTo(body);
            assertThat(t.version()).isEqualTo(version); assertThat(t.updatedBy()).isEqualTo("account-admin"); assertThat(t.customized()).isTrue();
        });
        assertThat(changes(n09)).hasSize(events); assertThat(audits(n09)).hasSize(entries);
        assertThat(detail(CLUB, n09).path("lastChange").path("actorName").asText()).isEqualTo("Example Admin");
        assertThat(detail(CLUB, n09).path("bodyI18n").fieldNames()).toIterable().containsExactly("ca", "es");
        locales(CLUB, "ca", "es", "en"); // and adds it back
        assertThat(detail(CLUB, n09).at("/bodyI18n/en").asText()).isEqualTo("Well done! Your bookings stay valid.");
        assertThat(detail(CLUB, n09).at("/titleI18n/en").asText()).isEqualTo("[[dog_name]] moves up to [[level_name]]!");
        System.out.println("E7-T06 round 2, review #1: after the restart N-09 en = " + reread(n09).body().values().get("en") + " (version " + reread(n09).version() + ")");
    }

    /**
     * Round 2, review #3 and #4: an N-08b the club edited while E7-T03's seed printed `{class_description}` (no N-08b variable)
     * renders it empty and cannot be saved unchanged; the upgrade removes it and keeps the club's words, so D9's GET → unchanged
     * PUT answers 200; the change is a `MessageTemplateChanged` and a `CATALOG_CHANGED` by the upgrade.
     */
    @Test void E7_T06_anEditedN08bLosesClassDescriptionAndSavesUnchanged() throws Exception {
        var seed08b = MessageTemplateSeed.load().of("N-08b").orElseThrow();
        var body = only(E7T03_N08B_BODY, "ca", "es"); body.put("ca", "[[class_date]] · {class_description}, amb [[dog_name]]: la classe ha canviat. [[changes]]. Truca'ns si cal.");
        var n08b = stored(CLUB, "N-08b", only(seed08b.title(), "ca", "es"), body, only(seed08b.smsBody(), "ca", "es"), true);
        var refused = getThenPutUnchanged(CLUB, n08b, 400);
        assertThat(refused.path("code").asText()).isEqualTo("TEMPLATE_UNKNOWN_VARIABLE");
        assertThat(refused.at("/details/variables").toString()).isEqualTo("[\"class_description\"]");

        upgrade.afterSingletonsInstantiated();

        assertThat(reread(n08b)).satisfies(t -> {
            assertThat(t.body().values()).isEqualTo(Map.of("ca", "[[class_date]], amb [[dog_name]]: la classe ha canviat. [[changes]]. Truca'ns si cal.",
                    "es", seed08b.body().get("es")));
            assertThat(t.smsBody().values()).isEqualTo(only(seed08b.smsBody(), "ca", "es")); assertThat(t.customized()).isTrue();
            assertThat(t.version()).isEqualTo(1); assertThat(t.updatedBy()).isEqualTo(ACTOR);
        });
        changedByTheUpgrade(CLUB, n08b, "body");
        var saved = getThenPutUnchanged(CLUB, n08b, 200);
        assertThat(saved.at("/bodyI18n/ca").asText()).isEqualTo("[[class_date]], amb [[dog_name]]: la classe ha canviat. [[changes]]. Truca'ns si cal.");
        assertThat(saved.path("version").asLong()).isEqualTo(2);
        System.out.println("E7-T06 round 2, review #3: N-08b ca " + reread(n08b).body().values().get("ca"));
    }

    /**
     * Round 2, review #3 and P1 (ruling E81): N-32c's `{date}` is valid since its row gained `date`. An N-32c the club edited
     * while E7-T03's seed printed `({date})` saves unchanged before and after the upgrade, which leaves it alone: nothing
     * written, published or audited.
     */
    @Test void E7_T06_anEditedN32cKeepsItsDateAndNothingIsEmitted() throws Exception {
        var seed32c = MessageTemplateSeed.load().of("N-32c").orElseThrow();
        var sms = only(E7T03_N32C_SMS, "ca", "es"); sms.put("ca", "[[club_name]]: [[activity_title]] ({date}) queda anul·lada. [[admin_text]]");
        var n32c = stored(CLUB, "N-32c", only(seed32c.title(), "ca", "es"), only(seed32c.body(), "ca", "es"), sms, true);
        assertThat(getThenPutUnchanged(CLUB, n32c, 200).path("version").asLong()).isEqualTo(1);
        int events = changes(n32c).size(), entries = audits(n32c).size();

        upgrade.afterSingletonsInstantiated();

        assertThat(reread(n32c).smsBody().values()).isEqualTo(sms); assertThat(reread(n32c).version()).isEqualTo(1);
        assertThat(changes(n32c)).hasSize(events); assertThat(audits(n32c)).hasSize(entries);
        assertThat(getThenPutUnchanged(CLUB, n32c, 200).path("version").asLong()).isEqualTo(2);
        System.out.println("E7-T06 round 2, P1: N-32c SMS ca " + reread(n32c).smsBody().values().get("ca"));
    }

    /**
     * Round 2, review #2 (ruling E81): one club never stops the start-up. The Buenos Aires club holds a template the API
     * cannot read (a `kind` no version ever wrote), and its id sorts first. The start-up hook returns; the Madrid club's E7-T02
     * N-02 is upgraded all the same; the failing club is logged as a WARN with its id only and is tried again at the next
     * start-up, which upgrades it once it can be read; and the API answers. Before the fix the exception left the hook and
     * the context did not start.
     */
    @Test void E7_T06_aClubThatFailsNeverStopsTheOtherClubsNorTheStartUp() throws Exception {
        var n02 = stored(CLUB, "N-02", E7T02_N02_TITLE, E7T02_N02_BODY, null, false);
        var round1 = stored(OTHER, "N-02", ROUND1_N02_TITLE, ROUND1_N02_BODY, null, false);
        assertThat(OTHER).isLessThan(CLUB); // the failing club is upgraded first
        var unreadable = new Document("_id", "e7t06-unreadable").append("clubId", OTHER).append("code", "N-09").append("kind", "BOGUS").append("category", "PERSONAL")
                .append("status", "ACTIVE").append("version", 0);
        mongo.insert(unreadable, "message_templates");
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); var logger = (Logger) LoggerFactory.getLogger(TemplateUpgrade.class); logger.addAppender(logs);
        try {
            assertThatCode(upgrade::afterSingletonsInstantiated).doesNotThrowAnyException(); // the API's start-up goes on

            assertThat(reread(n02).body().values().get("ca")).isEqualTo("Ja tens accés a l'app del club."); assertThat(reread(n02).updatedBy()).isEqualTo(ACTOR);
            assertThat(reread(round1).body().values()).isEqualTo(ROUND1_N02_BODY); // its club failed: tried again next time
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.startsWith("Message templates of a club not brought up to date club=" + OTHER + " error="))
                    .noneMatch(message -> message.contains("BOGUS") || message.contains("Entra-hi") || message.contains(CLUB));
            assertThat(call(admin(get("/api/v1/message-templates/" + n02.id()), CLUB), 200).path("version").asLong()).isEqualTo(1);
            assertThat(call(admin(get("/api/v1/health"), CLUB), 200).path("status").asText()).isEqualTo("UP");
        } finally {
            mongo.remove(Query.query(Criteria.where("_id").is("e7t06-unreadable")), "message_templates");
            logger.detachAppender(logs);
        }
        // The next start-up reads the club again and upgrades it.
        upgrade.afterSingletonsInstantiated();
        assertThat(reread(round1).body().values()).isEqualTo(seed(OTHER, "N-02", List.of("ca", "es", "en")).body().values());
        System.out.println("E7-T06 round 2, review #2: " + OTHER + " failed and was retried; " + CLUB + " upgraded: " + reread(n02).body().values().get("ca"));
    }
}
