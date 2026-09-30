package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateProvider;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.definition.ClubDefinitionCodec;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E7-T03 steps 1–3 against the API (S11 R-11-12, D9): T-11-16 the list with its counts (the seed of every eligible code
 * created on first use), `CUSTOM` creation, save with `version`, archive, `MessageTemplateChanged` in the outbox and
 * `CATALOG_CHANGED` in the audit; T-11-12 the validations and the reset to the seed with `customized = false`; T-11-17 the
 * preview of an unsaved draft in `ca`, `es` and `en` and «envia prova»; T-11-21 the SMS column with `SMS` off; T-11-29 another
 * club's template is 404; and step 3's `club:apply` section.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class MessageTemplatesIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t03-tpl-a", OTHER = "e7t03-tpl-b", HOST = "tpl-a.example.test", OTHER_HOST = "tpl-b.example.test", ADMIN = "e7t03-tpl-admin";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts; @Autowired EmailSender email; @Autowired TemplateProvider provider; @Autowired ClubDefinitions definitions;
    @Autowired ClubDefinitionCodec codec;

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-10-05T08:00:00Z"));
        club(CLUB, List.of(Module.values())); club(OTHER, List.of(Module.values()));
        hosts.invalidate();
        for (String collection : List.of("message_templates", "notifications", "domain_events", "audit_entries")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        mongo.save(new com.agilityhub.core.identity.persistence.Account(ADMIN, "tpl.admin@example.test", "Aleix Example", "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
        ((FakeEmailSender) email).clear();
    }
    private void club(String clubId, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
        tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) { return as(request, role, CLUB, "ca"); }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role, String clubId, String locale) {
        return request.header("Host", clubId.equals(CLUB) ? HOST : OTHER_HOST).contentType("application/json")
                .with(jwt().jwt(j -> j.subject(role.equals("ADMIN") ? ADMIN : "e7t03-tpl-" + role).claim("clubId", clubId).claim("locale", locale))
                        .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private JsonNode ok(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isEmpty() ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }
    private JsonNode error(MockHttpServletRequestBuilder request, int status, ErrorCode code) throws Exception {
        var body = ok(request, status);
        assertThat(body.path("code").asText()).isEqualTo(code.name());
        assertThat(body.path("traceId").asText()).isNotEmpty(); assertThat(body.path("message").asText()).isNotEmpty();
        return body;
    }
    private JsonNode list() throws Exception { return ok(as(get("/api/v1/message-templates"), "ADMIN"), 200); }
    private JsonNode item(JsonNode list, String code) {
        for (var item : list.path("items")) { if (code.equals(item.path("code").asText())) { return item; } }
        throw new AssertionError("no " + code);
    }
    private JsonNode detail(String id) throws Exception { return ok(as(get("/api/v1/message-templates/" + id), "ADMIN"), 200); }
    /** The PUT body of a detail, with changes. */
    private ObjectNode save(JsonNode detail) {
        var body = mapper.createObjectNode();
        body.set("title", detail.path("titleI18n").deepCopy()); body.set("body", detail.path("bodyI18n").deepCopy());
        body.set("smsBody", detail.path("smsBodyI18n").deepCopy()); body.put("icon", detail.path("icon").asText()); body.put("color", detail.path("color").asText());
        body.set("matrix", detail.path("matrix").deepCopy()); body.put("enabled", detail.path("enabled").asBoolean()); body.put("version", detail.path("version").asLong());
        return body;
    }
    private List<Document> events(String type) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is(type)), Document.class, "domain_events");
    }
    private List<Document> audits(String entityId) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("entityId").is(entityId).and("action").is("CATALOG_CHANGED")), Document.class, "audit_entries");
    }

    @Test void T_11_16_theListSeedsEveryEligibleCodeOnceInD9OrderWithTheCountsPerCategory() throws Exception {
        var list = list();
        var seeded = MessageTemplateSeed.eligible();
        assertThat(list.path("items")).hasSize(seeded.size());
        var codes = new ArrayList<String>(); list.path("items").forEach(i -> codes.add(i.path("code").asText()));
        var categories = new ArrayList<String>(); list.path("items").forEach(i -> categories.add(i.path("category").asText()));
        // D9's order: category OPERATIONAL · PERSONAL · CLUB_CHANGES · CLUB_NEWS, and the catalog's order inside each.
        assertThat(categories).isSortedAccordingTo(java.util.Comparator.comparingInt(List.of("OPERATIONAL", "PERSONAL", "CLUB_CHANGES", "CLUB_NEWS")::indexOf));
        assertThat(codes).containsExactlyInAnyOrderElementsOf(seeded.stream().map(s -> s.code()).toList());
        var counts = list.path("countsByCategory");
        for (String category : List.of("OPERATIONAL", "PERSONAL", "CLUB_CHANGES", "CLUB_NEWS")) {
            assertThat(counts.path(category).asInt()).as(category).isEqualTo((int) seeded.stream().filter(s -> s.category().name().equals(category)).count());
        }
        System.out.println("E7-T03 D9 list: " + codes.size() + " templates, counts " + counts);
        var n08a = item(list, "N-08a");
        assertThat(n08a.path("kind").asText()).isEqualTo("CATALOG"); assertThat(n08a.path("name").asText()).isEqualTo("Classe anul·lada pel club");
        assertThat(n08a.path("icon").asText()).isEqualTo("x"); assertThat(n08a.path("color").asText()).isEqualTo("ERROR");
        assertThat(n08a.at("/caps/MEMBER")).hasToString("[\"APP\",\"EMAIL\",\"SMS\"]"); assertThat(n08a.at("/caps/INSTRUCTORS")).hasToString("[\"APP\",\"EMAIL\"]");
        assertThat(n08a.at("/matrix/MEMBER/SMS").asBoolean()).isTrue(); assertThat(n08a.at("/matrix/ADMINS/EMAIL").asBoolean()).isFalse();
        assertThat(n08a.path("push")).isEmpty(); assertThat(item(list, "N-13").path("push")).hasToString("[\"MEMBER\"]");
        assertThat(n08a.path("lastChange").isNull()).isTrue(); assertThat(n08a.path("customized").asBoolean()).isFalse();
        // Variables: the code's own, labelled in the admin's language (D9's chips), the code key as the value.
        var labels = new java.util.LinkedHashMap<String, String>(); n08a.path("variables").forEach(v -> labels.put(v.path("key").asText(), v.path("label").asText()));
        assertThat(labels).containsEntry("admin_text", "text_admin").containsEntry("class_date", "classe_data").containsEntry("club_name", "entitat_nom")
                .containsEntry("member_first_name", "persona_nom").doesNotContainKey("effective_date");
        var spanish = ok(as(get("/api/v1/message-templates").param("category", "PERSONAL"), "ADMIN", CLUB, "es"), 200);
        assertThat(item(spanish, "N-28").path("variables").findValuesAsText("label")).contains("persona_fecha_baja", "persona_nombre");
        assertThat(spanish.path("items")).allSatisfy(i -> assertThat(i.path("category").asText()).isEqualTo("PERSONAL"));
        // A second read creates nothing more; the seed is in the club's three languages, not customized.
        list();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), MessageTemplate.class)).isEqualTo(seeded.size());
        var detail = detail(n08a.path("id").asText());
        assertThat(detail.path("bodyI18n").fieldNames()).toIterable().containsExactly("ca", "es", "en");
        assertThat(detail.at("/seedDefault/smsBodyI18n/ca").asText()).startsWith("[[club_name]]: la classe de [[class_date]]");
        assertThat(detail.path("mandatory").asBoolean()).isTrue(); assertThat(detail.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(detail(item(list, "N-04").path("id").asText()).path("smsBodyI18n").isNull()).isTrue();
        // Nothing of the other club.
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER)), MessageTemplate.class)).isZero();
    }

    @Test @AuditCovers(AuditAction.CATALOG_CHANGED)
    void T_11_16_aCustomTemplateIsCreatedSavedWithItsVersionAndArchived() throws Exception {
        var body = mapper.readTree("""
                {"category": "CLUB_NEWS", "title": {"ca": "Festa del club", "es": "Fiesta del club"}, "body": {"ca": "Hola [[member_first_name]], us esperem!"},
                 "icon": "flag", "color": "ACCENT", "matrix": {"MEMBER": {"APP": true, "EMAIL": true, "SMS": false},
                 "INSTRUCTORS": {"APP": false, "EMAIL": false, "SMS": false}, "ADMINS": {"APP": false, "EMAIL": false, "SMS": false}}}""");
        var created = ok(as(post("/api/v1/message-templates").content(body.toString()), "ADMIN"), 201);
        String id = created.path("id").asText();
        assertThat(created.path("kind").asText()).isEqualTo("CUSTOM"); assertThat(created.path("code").isNull()).isTrue();
        assertThat(created.path("version").asLong()).isZero(); assertThat(created.path("seedDefault").isNull()).isTrue();
        assertThat(created.path("variables").findValuesAsText("key")).containsExactly("member_name", "member_first_name", "member_last_names", "gender", "dog_name", "club_name");
        assertThat(created.at("/caps/MEMBER")).hasToString("[\"APP\",\"EMAIL\"]"); assertThat(created.at("/caps/ADMINS")).isEmpty();
        assertThat(events("MessageTemplateChanged")).singleElement().satisfies(e -> {
            assertThat(e.get("payload", Document.class).getString("id")).isEqualTo(id);
            assertThat(e.get("payload", Document.class).get("diff", Document.class)).containsKeys("title", "body", "category", "matrix", "status");
            assertThat(e.get("payload", Document.class).get("diff", Document.class).get("title", Document.class).get("after", Document.class))
                    .containsEntry("ca", "Festa del club").containsEntry("es", "Fiesta del club");
        });
        assertThat(audits(id)).singleElement().satisfies(a -> assertThat(a.getString("entityType")).isEqualTo("MessageTemplate"));
        // Saved with its version; the stale version is 409.
        var edit = save(created); edit.withObject("/body").put("ca", "Hola [[member_first_name]], us esperem dissabte!");
        var saved = ok(as(put("/api/v1/message-templates/" + id).content(edit.toString()), "ADMIN"), 200);
        assertThat(saved.path("version").asLong()).isEqualTo(1); assertThat(saved.at("/bodyI18n/ca").asText()).endsWith("dissabte!");
        assertThat(saved.path("lastChange").path("action").asText()).isEqualTo("CATALOG_CHANGED");
        error(as(put("/api/v1/message-templates/" + id).content(edit.toString()), "ADMIN"), 409, ErrorCode.STALE_VERSION);
        // The category of a CUSTOM one may change; OPERATIONAL or SYSTEM may not be a CUSTOM category.
        var changes = save(saved); changes.put("category", "PERSONAL");
        assertThat(ok(as(put("/api/v1/message-templates/" + id).content(changes.toString()), "ADMIN"), 200).path("category").asText()).isEqualTo("PERSONAL");
        ((ObjectNode) body).put("category", "OPERATIONAL");
        assertThat(error(as(post("/api/v1/message-templates").content(body.toString()), "ADMIN"), 400, ErrorCode.VALIDATION_ERROR).at("/details/field").asText()).isEqualTo("category");
        // A reset needs a catalog template; archiving a CUSTOM one hides it (404), the list keeps it only with includeArchived.
        error(as(post("/api/v1/message-templates/" + id + "/reset"), "ADMIN"), 422, ErrorCode.TEMPLATE_NOT_CATALOG);
        ok(as(delete("/api/v1/message-templates/" + id), "ADMIN"), 204);
        error(as(get("/api/v1/message-templates/" + id), "ADMIN"), 404, ErrorCode.NOT_FOUND);
        error(as(delete("/api/v1/message-templates/" + id), "ADMIN"), 404, ErrorCode.NOT_FOUND);
        var all = ok(as(get("/api/v1/message-templates").param("includeArchived", "true").param("kind", "CUSTOM"), "ADMIN"), 200);
        assertThat(all.path("items")).singleElement().satisfies(i -> assertThat(i.path("id").asText()).isEqualTo(id));
        assertThat(ok(as(get("/api/v1/message-templates").param("kind", "CUSTOM"), "ADMIN"), 200).path("items")).isEmpty();
        assertThat(mongo.findById(id, Document.class, "message_templates").getString("status")).isEqualTo("ARCHIVED");
        assertThat(events("MessageTemplateChanged")).hasSize(4); assertThat(audits(id)).hasSize(4);
        // A catalog template is never deleted.
        error(as(delete("/api/v1/message-templates/" + item(list(), "N-04").path("id").asText()), "ADMIN"), 422, ErrorCode.TEMPLATE_NOT_CUSTOM);
    }

    @Test void T_11_12_theSaveValidationsAndTheResetToTheSeed() throws Exception {
        var list = list();
        // «Canvi de nivell» (N-09) with SMS for the member → 422 CHANNEL_NOT_ALLOWED.
        var n09 = detail(item(list, "N-09").path("id").asText());
        var sms = save(n09); sms.withObject("/matrix").withObject("/MEMBER").put("SMS", true);
        assertThat(error(as(put("/api/v1/message-templates/" + n09.path("id").asText()).content(sms.toString()), "ADMIN"), 422, ErrorCode.CHANNEL_NOT_ALLOWED)
                .at("/details/cells/0/channel").asText()).isEqualTo("SMS");
        // N-02 without [[link]] → 400 VALIDATION_ERROR with details.missingVariables.
        var n02 = detail(item(list, "N-02").path("id").asText());
        var noLink = save(n02); noLink.withObject("/body").put("ca", "Ja tens accés a l'app del club.");
        assertThat(error(as(put("/api/v1/message-templates/" + n02.path("id").asText()).content(noLink.toString()), "ADMIN"), 400, ErrorCode.VALIDATION_ERROR)
                .at("/details/missingVariables")).hasToString("[\"link\"]");
        // Unbalanced braces → 400 TEMPLATE_SYNTAX_ERROR; an unknown variable → 400 TEMPLATE_UNKNOWN_VARIABLE.
        var broken = save(n02); broken.withObject("/title").put("ca", "{gender, select, female {Benvinguda} other {Benvingut}");
        error(as(put("/api/v1/message-templates/" + n02.path("id").asText()).content(broken.toString()), "ADMIN"), 400, ErrorCode.TEMPLATE_SYNTAX_ERROR);
        var unknown = save(n02); unknown.withObject("/body").put("es", "Hola [[apodo]]: [[link]]");
        assertThat(error(as(put("/api/v1/message-templates/" + n02.path("id").asText()).content(unknown.toString()), "ADMIN"), 400, ErrorCode.TEMPLATE_UNKNOWN_VARIABLE)
                .at("/details/variables")).hasToString("[\"apodo\"]");
        // A mandatory template cannot be disabled (N-08a) → 422 TEMPLATE_MANDATORY; a non-mandatory one can (N-04).
        var n08a = detail(item(list, "N-08a").path("id").asText());
        var off = save(n08a); off.put("enabled", false);
        error(as(put("/api/v1/message-templates/" + n08a.path("id").asText()).content(off.toString()), "ADMIN"), 422, ErrorCode.TEMPLATE_MANDATORY);
        var n04 = detail(item(list, "N-04").path("id").asText());
        var disabled = save(n04); disabled.put("enabled", false);
        var saved04 = ok(as(put("/api/v1/message-templates/" + n04.path("id").asText()).content(disabled.toString()), "ADMIN"), 200);
        assertThat(saved04.path("status").asText()).isEqualTo("DISABLED"); assertThat(saved04.path("customized").asBoolean()).isFalse();
        // The rejected saves wrote nothing.
        assertThat(events("MessageTemplateChanged")).hasSize(1);
        // N-08a edited → customized; the reset restores the seed exactly and clears it.
        var edited = save(n08a); edited.withObject("/body").put("ca", "[[class_date]] · [[class_time]]: classe anul·lada. «[[admin_text]]»");
        edited.put("icon", "warn"); edited.withObject("/matrix").withObject("/INSTRUCTORS").put("EMAIL", false);
        var customized = ok(as(put("/api/v1/message-templates/" + n08a.path("id").asText()).content(edited.toString()), "ADMIN"), 200);
        assertThat(customized.path("customized").asBoolean()).isTrue(); assertThat(customized.path("version").asLong()).isEqualTo(1);
        // No template cache: the engine's next read is the saved text.
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(provider.forCode(NotificationCatalog.byCode("N-08a").orElseThrow(), "ca").body().values().get("ca")).endsWith("«[[admin_text]]»");
        }
        var reset = ok(as(post("/api/v1/message-templates/" + n08a.path("id").asText() + "/reset"), "ADMIN"), 200);
        assertThat(reset.path("customized").asBoolean()).isFalse(); assertThat(reset.path("version").asLong()).isEqualTo(2);
        assertThat(reset.path("bodyI18n")).isEqualTo(n08a.path("seedDefault").path("bodyI18n"));
        assertThat(reset.path("smsBodyI18n")).isEqualTo(n08a.path("seedDefault").path("smsBodyI18n"));
        assertThat(reset.path("icon").asText()).isEqualTo("x"); assertThat(reset.path("matrix")).isEqualTo(n08a.path("matrix"));
        // The edit and the reset: each diff names the body, the icon, the matrix cell and `customized`, in the outbox and in the audit.
        var changes = events("MessageTemplateChanged").stream().filter(e -> n08a.path("id").asText().equals(e.get("payload", Document.class).getString("id"))).toList();
        assertThat(changes).hasSize(2).allSatisfy(e -> assertThat(e.get("payload", Document.class).get("diff", Document.class))
                .containsKeys("body", "icon", "matrix", "customized").doesNotContainKey("title"));
        assertThat(audits(n08a.path("id").asText())).hasSize(2).allSatisfy(a -> assertThat(a.getList("changes", Document.class))
                .extracting(c -> c.getString("path")).contains("body.ca", "icon", "customized"));
        audits(n08a.path("id").asText()).forEach(a -> System.out.println("E7-T03 CATALOG_CHANGED: " + a.getString("action") + " " + a.getString("entityType") + " "
                + a.getString("entityId") + " actor=" + a.getString("actorAccountId") + " changes=" + a.getList("changes", Document.class).stream().map(c -> c.getString("path")).toList()));
    }

    @Test void T_11_17_previewOfAnUnsavedDraftInCaEsEnWithTheSmsCountersAndWarnings() throws Exception {
        String id = item(list(), "N-08a").path("id").asText();
        long notifications = mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        var ca = ok(as(post("/api/v1/message-templates/" + id + "/preview").content("{\"locale\":\"ca\"}"), "ADMIN"), 200);
        assertThat(ca.path("title").asText()).isEqualTo("Classe anul·lada pel club");
        assertThat(ca.path("body").asText()).startsWith("Dimecres 12 · 18:50 · B+C, amb Duna. «La classe queda anul·lada per la pluja.")
                .endsWith("— Example Agility Club. Aquesta sessió no compta al teu còmput.");
        assertThat(ca.path("emailSubject").asText()).isEqualTo("Classe anul·lada pel club");
        assertThat(ca.path("emailHtml").asText()).contains("Example Agility Club", "Dimecres 12");
        // The rain text makes the SMS longer than 160: cut, one segment, and a warning (never an error in the preview).
        assertThat(ca.at("/sms/truncated").asBoolean()).isTrue(); assertThat(ca.at("/sms/length").asInt()).isLessThanOrEqualTo(160);
        assertThat(ca.at("/sms/segments").asInt()).isEqualTo(1); assertThat(ca.at("/sms/text").asText()).startsWith("Example Agility Club: la classe de dimecres 12 a les 18:50");
        assertThat(ca.path("warnings")).singleElement().satisfies(w -> assertThat(w.path("code").asText()).isEqualTo("SMS_BODY_TOO_LONG"));
        for (var language : List.of(List.of("es", "Clase cancelada por el club", "Miércoles 12 · 18:50"), List.of("en", "Class cancelled by the club", "12 Wednesday · 18:50"))) {
            var draft = mapper.createObjectNode().put("locale", language.get(0));
            draft.putObject("draft").put("title", language.get(1)).put("body", "[[class_date]] · [[class_time]] · [[apodo]] · [[dog_name]]").put("smsBody", "[[club_name]]: [[class_date]]");
            var preview = ok(as(post("/api/v1/message-templates/" + id + "/preview").content(draft.toString()), "ADMIN"), 200);
            assertThat(preview.path("title").asText()).isEqualTo(language.get(1));
            assertThat(preview.path("body").asText()).startsWith(language.get(2)).endsWith("· Duna").doesNotContain("[[");
            var sms = preview.path("sms");
            assertThat(sms.path("text").asText()).startsWith("Example Agility Club: ").contains("12").doesNotContain("[[");
            assertThat(sms.path("length").asInt()).isEqualTo(sms.path("text").asText().length());
            assertThat(sms.path("segments").asInt()).isEqualTo(1); assertThat(sms.path("truncated").asBoolean()).isFalse();
            assertThat(preview.path("warnings")).singleElement().satisfies(w -> {
                assertThat(w.path("code").asText()).isEqualTo("TEMPLATE_UNKNOWN_VARIABLE"); assertThat(w.path("variable").asText()).isEqualTo("apodo"); });
            System.out.println("E7-T03 preview " + language.get(0) + ": " + preview.path("title").asText() + " | " + preview.path("body").asText() + " | " + preview.path("sms"));
        }
        // The draft is not saved, and nothing is written.
        assertThat(detail(id).at("/titleI18n/es").asText()).isEqualTo("Clase cancelada por el club");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class)).isEqualTo(notifications);
        assertThat(events("MessageTemplateChanged")).isEmpty();
        // ICU that does not parse is an error; a language outside the club is a validation error.
        error(as(post("/api/v1/message-templates/" + id + "/preview").content("{\"locale\":\"ca\",\"draft\":{\"title\":\"x\",\"body\":\"{kind, select\"}}"), "ADMIN"),
                400, ErrorCode.TEMPLATE_SYNTAX_ERROR);
        error(as(post("/api/v1/message-templates/" + id + "/preview").content("{\"locale\":\"fr\"}"), "ADMIN"), 400, ErrorCode.VALIDATION_ERROR);
    }

    @Test void T_11_17_sendTestDeliversOnceToTheActingAdminByAppAndEmailOnly() throws Exception {
        String id = item(list(), "N-08a").path("id").asText();
        ok(as(post("/api/v1/message-templates/" + id + "/preview").content("{\"locale\":\"es\",\"sendTest\":true}"), "ADMIN"), 200);
        var sent = mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class);
        assertThat(sent).singleElement().satisfies(n -> {
            assertThat(n.code()).isEqualTo("N-08a"); assertThat(n.audience().name()).isEqualTo("ADMINS"); assertThat(n.recipient().accountId()).isEqualTo(ADMIN);
            assertThat(n.dedupKey()).startsWith("test:" + id + ":" + ADMIN + ":"); assertThat(n.locale()).isEqualTo("es"); assertThat(n.title()).isEqualTo("Clase cancelada por el club");
            assertThat(n.action()).isNull(); assertThat(n.smsBody()).isNull(); assertThat(n.templateId()).isEqualTo(id);
            assertThat(n.deliveries()).extracting(d -> d.channel() + ":" + d.status()).containsExactly("APP:DELIVERED", "EMAIL:SENT");
        });
        assertThat(((FakeEmailSender) email).messages()).singleElement().satisfies(m -> { assertThat(m.to()).isEqualTo("tpl.admin@example.test");
            assertThat(m.subject()).isEqualTo("Clase cancelada por el club"); });
        assertThat(events("NotificationQueued")).hasSize(2);
        // It is in the admin's own feed and in the log.
        var feed = ok(as(get("/api/v1/me/notifications"), "ADMIN"), 200);
        assertThat(feed.path("items")).singleElement().satisfies(i -> assertThat(i.path("channels")).hasToString("[\"APP\"]"));
        assertThat(ok(as(get("/api/v1/notifications"), "ADMIN"), 200).path("totalItems").asLong()).isEqualTo(1);
    }

    @Test void T_11_12_T_11_17_rowsAndCategoriesATemplateDoesNotHaveAndThePreviewOfEveryKind() throws Exception {
        var list = list();
        // N-04 has no ADMINS row: an active cell there is outside its caps; a catalog template keeps the code's category.
        var n04 = detail(item(list, "N-04").path("id").asText());
        var admins = save(n04); admins.withObject("/matrix").withObject("/ADMINS").put("APP", true);
        assertThat(error(as(put("/api/v1/message-templates/" + n04.path("id").asText()).content(admins.toString()), "ADMIN"), 422, ErrorCode.CHANNEL_NOT_ALLOWED)
                .at("/details/cells/0/audience").asText()).isEqualTo("ADMINS");
        var category = save(n04); category.put("category", "CLUB_NEWS");
        error(as(put("/api/v1/message-templates/" + n04.path("id").asText()).content(category.toString()), "ADMIN"), 400, ErrorCode.VALIDATION_ERROR);
        // The preview of a template without SMS has no SMS; a CLUB_NEWS one carries the unsubscribe link in its e-mail.
        assertThat(ok(as(post("/api/v1/message-templates/" + n04.path("id").asText() + "/preview").content("{\"locale\":\"es\"}"), "ADMIN"), 200).path("sms").isNull()).isTrue();
        var news = ok(as(post("/api/v1/message-templates/" + item(list, "N-24").path("id").asText() + "/preview").content("{\"locale\":\"ca\"}"), "ADMIN"), 200);
        assertThat(news.path("emailHtml").asText()).contains("/comunicats/baixa?t=");
        // A CUSTOM template: its preview, a category it cannot take, and a test send to an admin without an e-mail address.
        var body = mapper.readTree("""
                {"category": "CLUB_CHANGES", "title": {"ca": "Canvi d'horari"}, "body": {"ca": "Hola [[member_first_name]]"}, "smsBody": {"ca": "[[club_name]]: canvi"},
                 "icon": "cal", "color": "WARNING", "matrix": {"MEMBER": {"APP": true, "EMAIL": false, "SMS": true},
                 "INSTRUCTORS": {"APP": false, "EMAIL": false, "SMS": false}, "ADMINS": {"APP": false, "EMAIL": false, "SMS": false}}}""");
        var custom = ok(as(post("/api/v1/message-templates").content(body.toString()), "ADMIN"), 201);
        assertThat(custom.at("/caps/MEMBER")).hasToString("[\"APP\",\"EMAIL\",\"SMS\"]"); assertThat(custom.path("push")).hasToString("[\"MEMBER\"]");
        var preview = ok(as(post("/api/v1/message-templates/" + custom.path("id").asText() + "/preview").content("{\"locale\":\"ca\"}"), "ADMIN"), 200);
        assertThat(preview.path("body").asText()).isEqualTo("Hola Laura"); assertThat(preview.at("/sms/text").asText()).isEqualTo("Example Agility Club: canvi");
        var news2 = save(custom); news2.put("category", "OPERATIONAL");
        error(as(put("/api/v1/message-templates/" + custom.path("id").asText()).content(news2.toString()), "ADMIN"), 400, ErrorCode.VALIDATION_ERROR);
        var noSms = save(custom); noSms.put("category", "PERSONAL"); noSms.putNull("smsBody");
        assertThat(error(as(put("/api/v1/message-templates/" + custom.path("id").asText()).content(noSms.toString()), "ADMIN"), 422, ErrorCode.CHANNEL_NOT_ALLOWED)
                .at("/details/cells/0/channel").asText()).isEqualTo("SMS");
        // The admin's address bounced (E1-T03): the test send keeps its APP copy and skips the e-mail.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(ADMIN)), new org.springframework.data.mongodb.core.query.Update().set("emailStatus", "BOUNCED"), "accounts");
        ok(as(post("/api/v1/message-templates/" + custom.path("id").asText() + "/preview").content("{\"locale\":\"ca\",\"sendTest\":true}"), "ADMIN"), 200);
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Notification.class)).singleElement().satisfies(n -> {
            assertThat(n.code()).isEqualTo("N-24"); assertThat(n.category().name()).isEqualTo("CLUB_CHANGES");
            assertThat(n.deliveries()).extracting(d -> d.channel() + ":" + d.status()).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_NO_CONTACT");
        });
        assertThat(((FakeEmailSender) email).messages()).isEmpty();
    }

    @Test void T_11_21_withSmsOffTheSmsColumnIsNotAvailableAndItsCellsAreKept() throws Exception {
        var modules = new ArrayList<>(List.of(Module.values())); modules.remove(Module.SMS); modules.remove(Module.PUSH);
        club(CLUB, modules);
        var list = list();
        var n08a = item(list, "N-08a");
        assertThat(n08a.at("/caps/MEMBER")).hasToString("[\"APP\",\"EMAIL\"]"); assertThat(n08a.at("/matrix/MEMBER/SMS").asBoolean()).isTrue();
        assertThat(item(list, "N-13").path("push")).isEmpty();
        // D9 sends the cell it cannot show as false: the stored cell stays.
        var body = save(detail(n08a.path("id").asText())); body.withObject("/matrix").withObject("/MEMBER").put("SMS", false);
        var saved = ok(as(put("/api/v1/message-templates/" + n08a.path("id").asText()).content(body.toString()), "ADMIN"), 200);
        assertThat(saved.at("/matrix/MEMBER/SMS").asBoolean()).isTrue();
    }

    @Test void T_11_28_T_11_29_onlyTheClubsAdminReachesItsTemplates() throws Exception {
        String id = item(list(), "N-04").path("id").asText();
        for (var request : List.of(get("/api/v1/message-templates/" + id), put("/api/v1/message-templates/" + id).content(save(detail(id)).toString()),
                post("/api/v1/message-templates/" + id + "/preview").content("{\"locale\":\"ca\"}"), post("/api/v1/message-templates/" + id + "/reset"),
                delete("/api/v1/message-templates/" + id))) {
            error(as(request, "ADMIN", OTHER, "ca"), 404, ErrorCode.NOT_FOUND);
        }
        for (String role : List.of("MEMBER", "INSTRUCTOR")) { error(as(get("/api/v1/message-templates"), role), 403, ErrorCode.FORBIDDEN); }
        assertThat(mongo.findById(id, Document.class, "message_templates").get("version")).isEqualTo(0L);
    }

    @Test void E7_T03_clubApplySeedsTheListedCodesInTheClubsLanguagesAndKeepsAnEditedOne() throws Exception {
        var input = codec.read(Path.of("seeds/club-minim.yaml")); input.remove("accounts");
        String slug = "tpl-" + UUID.randomUUID().toString().substring(0, 8);
        ((ObjectNode) input.get("club")).put("slug", slug); ((ObjectNode) input.at("/domains/0")).put("host", slug + ".example.test");
        var templates = input.putArray("messageTemplates");
        templates.addObject().put("code", "N-08a"); templates.addObject().put("code", "N-04");
        var result = definitions.apply(input, false);
        assertThat(result.render(false)).contains("+ messageTemplates.N-08a", "+ messageTemplates.N-04", "+ messageTemplates: 2 templates to seed");
        String club = result.id();
        var stored = mongo.find(Query.query(Criteria.where("clubId").is(club)), MessageTemplate.class);
        var locales = configs.get(club).club().locales();
        assertThat(stored).extracting(MessageTemplate::code).containsExactlyInAnyOrder("N-08a", "N-04");
        assertThat(stored).allSatisfy(t -> { assertThat(t.title().values().keySet()).containsExactlyInAnyOrderElementsOf(locales); assertThat(t.customized()).isFalse(); });
        // Applying again changes nothing; an edited template is kept (D9 wins once seeded); the export lists the seeded codes.
        mongo.updateFirst(Query.query(Criteria.where("clubId").is(club).and("code").is("N-04")), new org.springframework.data.mongodb.core.query.Update().set("enabled", false), MessageTemplate.class);
        assertThat(definitions.apply(input, false).render(false)).contains("= messageTemplates: 0 templates to seed").endsWith("0 changes (applied)");
        assertThat(mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("code").is("N-04")), MessageTemplate.class).enabled()).isFalse();
        assertThat(definitions.export(slug).path("messageTemplates")).hasToString("[{\"code\":\"N-04\"},{\"code\":\"N-08a\"}]");
        // A code twice is a duplicate; a code without a template (SYSTEM) or an unknown one is refused before any write.
        var twice = input.deepCopy(); ((com.fasterxml.jackson.databind.node.ArrayNode) twice.get("messageTemplates")).addObject().put("code", "N-04");
        assertThatThrownBy(() -> definitions.apply(twice, true)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DUPLICATE_NAME));
        for (String code : List.of("N-25", "N-99")) {
            var wrong = input.deepCopy(); ((ObjectNode) wrong.get("messageTemplates").get(0)).put("code", code);
            assertThatThrownBy(() -> definitions.apply(wrong, true)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
    }
}
