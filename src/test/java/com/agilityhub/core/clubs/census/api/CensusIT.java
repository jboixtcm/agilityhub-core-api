package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.application.*;
import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.identity.application.*;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.agilityhub.core.clubs.census.application.CensusValues.object;

@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "shared.scheduling.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CensusIT extends AbstractIntegrationTest {
    static final String CLUB = "census-a", OTHER = "census-b";
    static final String IBAN = "ES9121000418450200051332";
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired TokenService tokens; @Autowired PasswordHasher passwords;
    @Autowired ImpersonationService impersonation;
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired CensusRepository<Member> members; @Autowired CensusRepository<Dog> dogs;
    @Autowired CensusRepository<FamilyGroup> groups; @Autowired CensusRepository<DogDocument> documents;
    @Autowired MemberBookingEligibility eligibility; @Autowired MemberStatusService statuses;
    @Autowired IdentityTransactions transactions; @Autowired EventPublisher publisher; @Autowired OutboxDispatcher dispatcher;
    @Autowired CountryContacts countries; @Autowired AttachmentService attachments; @Autowired MemberIdentityAccess identityAccess;

    @BeforeEach void seed() {
        TenantContext.clear();
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "members", "dogs", "family_groups", "dog_documents",
                "levels", "plans", "prices", "instructors", "tasks", "attachments", "attachment_uploads", "inactivity_periods", "pack_balances",
                "bookings", "training_bookings", "waitlist_entries", "class_sessions", "audit_entries", "domain_events", "notifications", "magic_link_tokens",
                "attachment_write_locks", "impersonation_grants", "refresh_tokens", "account_sessions", "census_write_locks", "catalog_write_locks", "team_write_locks", "invoices")) { mongo.remove(new Query(), collection); }
        club(CLUB, Set.of(Module.values())); club(OTHER, Set.of(Module.values()));
        member(CLUB, "one", "ACTIVE", 1, "12345678Z", Set.of(Role.MEMBER));
        member(CLUB, "two", "ACTIVE", 2, "X1234567L", Set.of(Role.MEMBER));
        member(CLUB, "admin", "ACTIVE", 3, "00000000T", Set.of(Role.MEMBER, Role.ADMIN));
        member(OTHER, "foreign", "ACTIVE", 1, "12345678Z", Set.of(Role.MEMBER, Role.ADMIN));
        level(CLUB, "level-c", false); level(CLUB, "level-d", true); level(OTHER, "foreign-level", true);
        dog(CLUB, "dog-one", "one", "level-c", "100001"); dog(CLUB, "dog-two", "one", "level-d", "100002");
        dog(CLUB, "dog-family", "two", "level-c", "100003"); dog(OTHER, "dog-foreign", "foreign", "foreign-level", "100001");
    }
    void club(String id, Set<Module> modules) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(clubs.findById(id).orElseGet(() -> PlatformFixtures.club(id, id + ".example.test")));
        node.set("modules", mapper.valueToTree(modules));
        node.set("paymentProviders", mapper.valueToTree(Map.of("SEPA_XML", Map.of("enabled", true), "MANUAL", Map.of("enabled", true))));
        clubs.save(mapper.convertValue(node, Club.class)); configs.invalidate(id); hosts.invalidate();
    }
    void parameter(String key, Object value) {
        mongo.upsert(Query.query(Criteria.where("clubId").is(CLUB).and("key").is(key).and("scopeRef").is(null)),
                new Update().set("value", value).set("type", value instanceof Boolean ? "bool" : "int").set("scope", "CLUB").set("version", 0).set("history", List.of()).set("updatedAt", clock.instant()), "parameters"); configs.invalidate(CLUB);
    }
    void member(String club, String id, String status, int number, String document, Set<Role> roles) {
        mongo.insert(new Account(id + "-account", id + "@example.test", "Example " + id, "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.insert(new Membership(id + "-membership", id + "-account", club, id, roles, Membership.Status.ACTIVE, Role.MEMBER));
        var member = new Member(); member.id = id; member.clubId = club; member.accountId = id + "-account"; member.memberNumber = number;
        member.idDocument = object("type", document.startsWith("X") ? "NIE" : "DNI", "number", document);
        member.firstName = "Example"; member.lastName1 = id; member.gender = "OTHER"; member.birthDate = LocalDate.of(1990, 1, 1);
        member.contactEmails = List.of(object("email", id + "@example.test", "bounced", false));
        member.phones = List.of(object("prefix", "+34", "number", "600000001", "label", "Primary"));
        member.address = object("street", "1 Example Street", "postalCode", "08349", "city", "Example Town", "country", "ES");
        member.status = status; member.joinedAt = Instant.parse("2020-01-01T00:00:00Z"); member.bookingBlock = object("active", false);
        member.consents = object("privacyPolicy", object("acceptedAt", clock.instant(), "version", "v1"), "imageRights", object("granted", true));
        try (var tenant = TenantContext.open(club)) { members.insert(member); }
    }
    void dog(String club, String id, String member, String level, String chip) {
        var dog = new Dog(); dog.id = id; dog.clubId = club; dog.memberId = member; dog.name = "Example " + id; dog.breed = "Example breed";
        dog.sex = "FEMALE"; dog.birthDate = LocalDate.of(2022, 3, 12); dog.chip = chip; dog.status = "ACTIVE"; dog.levelId = level;
        dog.registeredAt = Instant.parse("2023-01-01T00:00:00Z"); dog.levelAssignedAt = dog.registeredAt;
        dog.levelHistory = List.of(object("levelId", level, "from", dog.levelAssignedAt)); dog.licenses = List.of();
        try (var tenant = TenantContext.open(club)) { dogs.insert(dog); }
    }
    void level(String club, String id, boolean free) {
        mongo.insert(new Document("_id", id).append("clubId", club).append("code", id).append("name", new Document("en", "Example Level").append("ca", "Nivell exemple"))
                .append("nameKeys", List.of("en:" + id)).append("order", 1).append("active", true).append("grantsFreeTraining", free).append("version", 0), "levels");
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String account, String... roles) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject(account + "-account").claim("clubId", club)
                .claim("name", "Example Operator").claim("locale", "en")).authorities(Arrays.stream(roles)
                        .<org.springframework.security.core.GrantedAuthority>map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)).toList())));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "admin", "ADMIN"); }
    ResultActions own(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "one", "MEMBER"); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception { return request.contentType("application/json").content(mapper.writeValueAsString(body)); }
    JsonNode json(ResultActions result, int status) throws Exception {
        return mapper.readTree(result.andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    JsonNode change(String path, Map<String,Object> input) throws Exception { return json(admin(body(patch("/api/v1/" + path), input)), 200); }
    void field(String collection, String id, String key, Object value) { mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set(key, value), collection); }
    Member member(String id) { return mongo.findById(id, Member.class); }
    Dog dog(String id) { return mongo.findById(id, Dog.class); }
    List<DomainEventRecord> events(String type) { return mongo.find(Query.query(Criteria.where("type").is(type)), DomainEventRecord.class); }
    List<AuditEntry> audit(AuditAction action) { return mongo.find(Query.query(Criteria.where("action").is(action)), AuditEntry.class); }
    void error(ResultActions result, ErrorCode code) throws Exception { result.andExpect(status().is(code.httpStatus())).andExpect(jsonPath("$.code").value(code.name())); }
    void publish(String type, String entity, String id, Map<String,Object> payload) {
        try (var tenant = TenantContext.open(CLUB)) {
            transactions.run(() -> publisher.publish(new CensusEvent(type, CLUB, entity, id, clock.instant(), payload, null, null, DomainEvent.Origin.SYSTEM)));
        }
    }
    String family() throws Exception {
        return json(admin(body(post("/api/v1/family-groups"), Map.of("holderMemberId", "one", "memberIds", List.of("one", "two")))), 201).path("id").asText();
    }
    void reference(String collection, String id, String dog, String state, Instant starts) {
        mongo.insert(new Document("_id", id).append("clubId", CLUB).append("dogId", dog).append("state", state).append("startsAt", starts == null ? null : Date.from(starts)), collection);
    }
    JsonNode uploadUrl(String purpose, String type, byte[] data, boolean admin) throws Exception {
        var request = body(post("/api/v1/attachments/upload-url"), object("purpose", purpose, "fileName", "example.png", "mimeType", type, "sizeBytes", data.length));
        var grant = json(admin ? admin(request) : own(request), 201);
        var upload = put(grant.path("uploadUrl").asText()).contentType(type).content(data);
        (admin ? admin(upload) : own(upload)).andExpect(status().isNoContent()); return grant;
    }

    @Test void T_03_07_countryProfilesNormalizeContactsAndValidateDocuments() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(countries.document("DNI", "12345678Z")).isTrue(); assertThat(countries.document("NIE", "X1234567L")).isTrue();
            assertThat(countries.document("DNI", "12345678A")).isFalse(); assertThat(countries.postalCode("0834")).isFalse();
            assertThat(countries.phone(null, "600 000 001", "Primary")).containsEntry("prefix", "+34").containsEntry("number", "600000001");
            assertThat(countries.phone("+1", "+12025550101", null)).containsEntry("prefix", "+1").containsEntry("number", "2025550101");
            assertThatThrownBy(() -> countries.phone(null, "60000000", null)).hasMessage("INVALID_PHONE");
            assertThatThrownBy(() -> countries.phone("+34", "+12025550101", null)).hasMessage("INVALID_PHONE");
            assertThatThrownBy(() -> countries.phone(null, "+999123456", null)).hasMessage("INVALID_PHONE");
        }
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); node.put("countryProfile", "GENERIC");
        clubs.save(mapper.convertValue(node, Club.class)); configs.invalidate(CLUB);
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(countries.document("OTHER", "Example-42")).isTrue(); assertThat(countries.postalCode("EX 42")).isTrue();
            assertThatThrownBy(() -> countries.phone(null, "600000001", null)).hasMessage("INVALID_PHONE");
            assertThat(countries.phone("+54", "91112345678", null)).containsEntry("prefix", "+54");
        }
    }
    @Test @AuditCovers({AuditAction.MEMBER_UPDATED, AuditAction.SIGNUP_EDITED})
    void T_03_12_memberEditsUseVersionUniquenessAndExactAuditDiff() throws Exception {
        error(admin(body(patch("/api/v1/members/one"), Map.of("version", 0, "idDocument", Map.of("type", "NIE", "number", "X1234567L")))), ErrorCode.ID_DOCUMENT_ALREADY_EXISTS);
        change("members/one", Map.of("version", 0, "firstName", "Updated"));
        assertThat(events("MemberUpdated")).hasSize(1); assertThat(audit(AuditAction.MEMBER_UPDATED)).singleElement().satisfies(entry -> {
            assertThat(entry.changes()).singleElement().satisfies(change -> assertThat(change.path()).isEqualTo("firstName")); assertThat(entry.memberId()).isEqualTo("one");
        });
        error(admin(body(patch("/api/v1/members/one"), Map.of("version", 0, "firstName", "Stale"))), ErrorCode.STALE_VERSION);
        field("members", "one", "status", "PENDING"); change("members/one", Map.of("version", 1, "remarks", "Updated pending signup"));
        assertThat(events("SignupEdited")).hasSize(1); assertThat(audit(AuditAction.SIGNUP_EDITED)).hasSize(1);
        change("members/one", Map.of("version", 2, "remarks", "Updated pending signup")); assertThat(member("one").version()).isEqualTo(2);
    }
    @Test @AuditCovers(AuditAction.MEMBER_PAYMENT_METHOD_CHANGED)
    void T_03_13_paymentMethodsAreValidatedAndAlwaysMasked() throws Exception {
        error(admin(body(patch("/api/v1/members/one/payment-method"), Map.of("type", "CARD"))), ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED);
        error(admin(body(patch("/api/v1/members/one/payment-method"), Map.of("type", "SEPA_DD", "sepa", Map.of("iban", "ES0021000418450200051332", "holderName", "Example")))), ErrorCode.INVALID_IBAN);
        var payment = change("members/one/payment-method", Map.of("type", "SEPA_DD", "sepa", Map.of("iban", IBAN, "holderName", "Example")));
        assertThat(payment.path("maskedAccount").asText()).isEqualTo("···· ···· ···· ···· 1332");
        assertThat(json(admin(get("/api/v1/members/one")), 200).toString()).doesNotContain(IBAN, "\"iban\"");
        assertThat(mapper.writeValueAsString(audit(AuditAction.MEMBER_PAYMENT_METHOD_CHANGED))).doesNotContain(IBAN).contains("1332");
        assertThat(events("MemberPaymentMethodChanged")).singleElement().satisfies(event -> assertThat(event.payload()).containsEntry("masked", "···· ···· ···· ···· 1332"));
        change("members/one/payment-method", Map.of("type", "SEPA_DD", "sepa", Map.of("holderName", "Example")));
        assertThat(json(admin(get("/api/v1/members/one/overview")), 200).path("member").path("accountMissing").asBoolean()).isTrue();
        change("members/one/payment-method", Map.of("type", "MANUAL", "manual", Map.of("channel", "transfer")));
        var count = events("MemberPaymentMethodChanged").size(); change("members/one/payment-method", Map.of("type", "MANUAL", "manual", Map.of("channel", "transfer")));
        assertThat(events("MemberPaymentMethodChanged")).hasSize(count);
    }
    @Test @AuditCovers({AuditAction.BOOKING_BLOCK_SET, AuditAction.BOOKING_BLOCK_CLEARED})
    void T_03_14_bookingBlocksAreReversibleAndRejectDuplicateChanges() throws Exception {
        admin(body(post("/api/v1/members/one/booking-block"), Map.of("reason", "Example reason"))).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true));
        error(admin(body(post("/api/v1/members/one/booking-block"), Map.of("reason", "Again"))), ErrorCode.BOOKING_BLOCK_ALREADY_ACTIVE);
        admin(delete("/api/v1/members/one/booking-block")).andExpect(status().isNoContent());
        error(admin(delete("/api/v1/members/one/booking-block")), ErrorCode.BOOKING_BLOCK_NOT_ACTIVE);
        error(admin(body(post("/api/v1/members/one/booking-block"), Map.of())), ErrorCode.VALIDATION_ERROR);
        assertThat(events("BookingBlockChanged")).hasSize(2); assertThat(audit(AuditAction.BOOKING_BLOCK_SET)).hasSize(1); assertThat(audit(AuditAction.BOOKING_BLOCK_CLEARED)).hasSize(1);
    }
    @Test void T_03_15_ownerOrFamilyActorBlockPreventsNewBookingsOnly() throws Exception {
        family(); reference("bookings", "existing", "dog-one", "ACTIVE", clock.instant().plusSeconds(100));
        admin(body(post("/api/v1/members/one/booking-block"), Map.of("reason", "Example reason"))).andExpect(status().isCreated());
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> eligibility.check("two", "dog-one")).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.details()).containsEntry("reason", "Example reason"));
            assertThatThrownBy(() -> eligibility.check("one", "dog-family")).hasMessage("BOOKING_BLOCKED");
        }
        assertThat(mongo.getCollection("bookings").countDocuments()).isEqualTo(1);
        admin(delete("/api/v1/members/one/booking-block")).andExpect(status().isNoContent());
        try (var tenant = TenantContext.open(CLUB)) { eligibility.check("two", "dog-one"); eligibility.check("one", "dog-one"); }
    }
    @Test @AuditCovers(AuditAction.ACCESS_RESENT)
    void T_03_16_accessResendPublishesAndDeliversN27Once() throws Exception {
        admin(post("/api/v1/members/one/access-resend")).andExpect(status().isAccepted()).andExpect(jsonPath("$.sentTo").value("one@example.test"));
        assertThat(events("AccessResent")).hasSize(1); dispatcher.dispatch(); dispatcher.dispatch();
        assertThat(mongo.find(Query.query(Criteria.where("code").is("N-27")), com.agilityhub.core.clubs.messaging.persistence.Notification.class)).hasSize(1);
        assertThat(audit(AuditAction.ACCESS_RESENT)).hasSize(1);
        field("members", "one", "status", "PENDING"); error(admin(post("/api/v1/members/one/access-resend")), ErrorCode.MEMBER_NOT_ACTIVE);
    }
    @Test void T_03_17_realMemberIdentityRetainsInactiveCompatibilityAndRejectsErasure() {
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(identityAccess.impersonationAccount("one")).isEqualTo("one-account");
            field("members", "one", "status", "INACTIVE"); assertThat(identityAccess.impersonationAccount("one")).isEqualTo("one-account");
            field("members", "one", "status", "LEFT"); assertThatThrownBy(() -> identityAccess.impersonationAccount("one")).hasMessage("IMPERSONATION_DENIED");
            field("members", "one", "erasedAt", clock.instant()); assertThatThrownBy(() -> identityAccess.impersonationAccount("one")).hasMessage("MEMBER_ERASED");
            assertThatThrownBy(() -> identityAccess.impersonationAccount("foreign")).hasMessage("NOT_FOUND");
        }
    }
    @Test void T_03_18_rolesDelegateToTheTeamInvariantService() throws Exception {
        error(admin(body(put("/api/v1/members/one/roles"), Map.of("roles", List.of("INSTRUCTOR")))), ErrorCode.ROLE_MEMBER_REQUIRED);
        error(admin(body(put("/api/v1/members/admin/roles"), Map.of("roles", List.of("MEMBER")))), ErrorCode.CANNOT_CHANGE_OWN_ADMIN_ROLE);
        admin(body(put("/api/v1/members/one/roles"), Map.of("roles", List.of("MEMBER", "INSTRUCTOR")))).andExpect(status().isOk());
        assertThat(mongo.getCollection("instructors").countDocuments()).isEqualTo(1); assertThat(events("MembershipChanged")).hasSize(1);
    }
    @Test @AuditCovers(AuditAction.DOG_LEVEL_CHANGED)
    void T_03_19_levelChangeKeepsBookingsAndEmitsNoWarnings() throws Exception {
        reference("bookings", "existing", "dog-one", "ACTIVE", clock.instant().plusSeconds(100));
        var result = change("dogs/dog-one/level", Map.of("levelId", "level-d"));
        assertThat(result.has("warnings")).isFalse(); assertThat(result.has("levelAssignedAt")).isTrue();
        assertThat(dog("dog-one").levelHistory).hasSize(2); assertThat(dog("dog-one").levelHistory.getFirst()).containsKey("to");
        assertThat(events("DogLevelChanged")).hasSize(1); assertThat(events("DogFreeTrainingChanged")).hasSize(1); dispatcher.dispatch();
        assertThat(events("DogFreeTrainingChanged")).hasSize(1); assertThat(mongo.getCollection("bookings").countDocuments()).isEqualTo(1);
        error(admin(body(patch("/api/v1/dogs/dog-one/level"), Map.of("levelId", "level-d"))), ErrorCode.LEVEL_UNCHANGED);
        parameter("levels.enabled", false); error(admin(body(patch("/api/v1/dogs/dog-one/level"), Map.of("levelId", "level-c"))), ErrorCode.LEVELS_DISABLED);
    }
    @Test @AuditCovers(AuditAction.DOG_FREE_TRAINING_CHANGED)
    void T_03_20_freeOverrideOnlyEmitsAnEventForEffectiveChanges() throws Exception {
        change("dogs/dog-two/free-training", Map.of("override", true)); assertThat(events("DogFreeTrainingChanged")).isEmpty();
        change("dogs/dog-two/free-training", Map.of("override", false)); assertThat(events("DogFreeTrainingChanged")).hasSize(1);
        var reset = new LinkedHashMap<String,Object>(); reset.put("override", null);
        var result = change("dogs/dog-two/free-training", reset); assertThat(result.path("override").isNull()).isTrue(); assertThat(result.path("source").asText()).isEqualTo("LEVEL");
        assertThat(audit(AuditAction.DOG_FREE_TRAINING_CHANGED)).hasSize(3);
        club(CLUB, Set.of()); error(admin(body(patch("/api/v1/dogs/dog-two/free-training"), Map.of("override", true))), ErrorCode.MODULE_DISABLED);
    }
    @Test @AuditCovers(AuditAction.DOG_TRANSFERRED)
    void T_03_21_transferPreservesDogDataAndClearsSelections() throws Exception {
        field("members", "one", "lastDogForClass", "dog-one"); field("members", "one", "lastDogForTraining", "dog-one");
        field("members", "two", "status", "LEFT"); error(admin(body(post("/api/v1/dogs/dog-one/transfer"), Map.of("toMemberId", "two"))), ErrorCode.TARGET_MEMBER_NOT_ACTIVE);
        field("members", "two", "status", "ACTIVE"); reference("bookings", "future", "dog-one", "ACTIVE", clock.instant().plusSeconds(100));
        error(admin(body(post("/api/v1/dogs/dog-one/transfer"), Map.of("toMemberId", "two"))), ErrorCode.DOG_HAS_FUTURE_BOOKINGS);
        mongo.remove(new Query(), "bookings"); mongo.insert(new Document("_id", "pack").append("clubId", CLUB).append("dogId", "dog-one").append("remaining", 2).append("total", 10), "pack_balances");
        error(admin(body(post("/api/v1/dogs/dog-one/transfer"), Map.of("toMemberId", "two"))), ErrorCode.DOG_HAS_OPEN_PACK);
        mongo.remove(new Query(), "pack_balances"); var before = dog("dog-one").levelHistory;
        admin(body(post("/api/v1/dogs/dog-one/transfer"), Map.of("toMemberId", "two"))).andExpect(status().isOk()).andExpect(jsonPath("$.memberId").value("two"));
        assertThat(dog("dog-one").levelHistory).isEqualTo(before); assertThat(member("one").lastDogForClass).isNull(); assertThat(member("one").lastDogForTraining).isNull();
        assertThat(events("DogTransferred")).hasSize(1);
    }
    @Test @AuditCovers({AuditAction.DOG_DEACTIVATED, AuditAction.DOG_REACTIVATED})
    void T_03_22_deactivationChecksWaitlistsAndReactivationRequiresAnActiveOwner() throws Exception {
        reference("waitlist_entries", "future", "dog-one", "NOTIFIED", clock.instant().plusSeconds(100));
        error(admin(body(post("/api/v1/dogs/dog-one/deactivation"), Map.of())), ErrorCode.DOG_HAS_FUTURE_BOOKINGS);
        mongo.remove(new Query(), "waitlist_entries"); admin(body(post("/api/v1/dogs/dog-one/deactivation"), Map.of())).andExpect(status().isOk());
        assertThat(dog("dog-one").deactivationReason).isEqualTo("CLUB"); assertThat(json(own(get("/api/v1/me/dogs")), 200).path("dogs")).hasSize(1);
        field("members", "one", "status", "LEFT"); error(admin(body(post("/api/v1/dogs/dog-one/reactivation"), Map.of())), ErrorCode.TARGET_MEMBER_NOT_ACTIVE);
        field("members", "one", "status", "ACTIVE"); admin(body(post("/api/v1/dogs/dog-one/reactivation"), Map.of())).andExpect(status().isOk());
        assertThat(dog("dog-one").status).isEqualTo("ACTIVE"); assertThat(dog("dog-one").deactivatedAt).isNull(); assertThat(audit(AuditAction.DOG_REACTIVATED)).hasSize(1);
    }
    @Test @AuditCovers({AuditAction.DOG_UPDATED, AuditAction.DOG_DOCUMENT_FILE_REMOVED})
    void T_03_05_T_03_23_uploadOwnershipFileValidationAndSoftRemoval() throws Exception {
        error(own(body(post("/api/v1/attachments/upload-url"), object("purpose", "DOG_DOCUMENT", "fileName", "large.pdf", "mimeType", "application/pdf", "sizeBytes", 30 * 1024 * 1024))), ErrorCode.FILE_TOO_LARGE);
        error(own(body(post("/api/v1/attachments/upload-url"), object("purpose", "DOG_DOCUMENT", "fileName", "bad.exe", "mimeType", "application/x-msdownload", "sizeBytes", 10))), ErrorCode.FILE_TYPE_NOT_ALLOWED);
        var grant = uploadUrl("DOG_DOCUMENT", "application/pdf", "Example PDF".getBytes(), false);
        var input = Map.of("type", "VACCINATION_CARD", "name", "Example vaccination.pdf", "fileKey", grant.path("fileKey").asText());
        var doc = json(own(body(post("/api/v1/me/dogs/dog-one/documents"), input)), 201);
        assertThat(doc.path("state").asText()).isEqualTo("RECEIVED");
        var repeated = json(own(body(post("/api/v1/me/dogs/dog-one/documents"), input)), 201); assertThat(repeated.path("files")).hasSize(1);
        error(own(body(post("/api/v1/me/dogs/dog-family/documents"), input)), ErrorCode.FORBIDDEN);
        error(own(body(post("/api/v1/me/dogs/dog-one/documents"), Map.of("type", "UNKNOWN", "name", "Example", "fileKey", grant.path("fileKey").asText()))), ErrorCode.DOCUMENT_TYPE_UNKNOWN);
        String path = "/api/v1/dogs/dog-one/documents/" + doc.path("id").asText() + "/files/" + doc.path("files").get(0).path("id").asText();
        error(own(delete(path)), ErrorCode.FORBIDDEN); admin(delete(path)).andExpect(status().isNoContent()); admin(delete(path)).andExpect(status().isNoContent());
        assertThat(json(admin(get("/api/v1/dogs/dog-one/documents")), 200).get(0).path("state").asText()).isEqualTo("PENDING");
        assertThat(events("DogDocumentPending")).singleElement().satisfies(event -> assertThat(event.payload()).containsEntry("trigger", "FILE_REMOVED"));
        assertThat(audit(AuditAction.DOG_DOCUMENT_FILE_REMOVED)).hasSize(1);
        assertThat(mongo.findById(doc.path("id").asText(), DogDocument.class).files.getFirst()).containsKey("removedAt");
    }
    @Test void T_03_24_documentReminderHasA24HourWindowAndNoScheduledSideEffect() throws Exception {
        var request = Map.of("type", "VACCINATION_CARD");
        admin(body(post("/api/v1/dogs/dog-one/documents/reminder"), request)).andExpect(status().isAccepted());
        error(admin(body(post("/api/v1/dogs/dog-one/documents/reminder"), request)), ErrorCode.DOCUMENT_REMINDER_TOO_SOON);
        clock.advance(Duration.ofHours(24)); admin(body(post("/api/v1/dogs/dog-one/documents/reminder"), request)).andExpect(status().isAccepted());
        assertThat(events("DogDocumentPending")).hasSize(2); assertThat(events("DocumentReminderDue")).isEmpty();
        var grant = uploadUrl("DOG_DOCUMENT", "application/pdf", "Example PDF".getBytes(), true);
        admin(body(post("/api/v1/dogs/dog-one/documents"), object("type", "VACCINATION_CARD", "name", "Example", "fileKey", grant.path("fileKey").asText()))).andExpect(status().isCreated());
        error(admin(body(post("/api/v1/dogs/dog-one/documents/reminder"), request)), ErrorCode.DOCUMENT_NOT_PENDING);
        error(admin(body(post("/api/v1/dogs/dog-one/documents/reminder"), Map.of("type", "INSURANCE"))), ErrorCode.DOCUMENT_NOT_PENDING);
    }
    @Test void T_03_25_instructorNotesAreOwnerOnlyAndNoOpSavesEmitNothing() throws Exception {
        var request = body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", "Please practice waiting"));
        own(request).andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Please practice waiting"));
        own(body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", "Please practice waiting"))).andExpect(status().isOk());
        assertThat(events("MemberNoteChanged")).hasSize(1);
        own(body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", ""))).andExpect(status().isOk()); assertThat(events("MemberNoteChanged")).hasSize(2);
        error(own(body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", "x".repeat(2001)))), ErrorCode.VALIDATION_ERROR);
        error(call(body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", "No")), CLUB, "one", "MEMBER", "INSTRUCTOR"), ErrorCode.FORBIDDEN);
        error(admin(body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", "No"))), ErrorCode.FORBIDDEN);
        error(own(body(put("/api/v1/me/dogs/dog-family/instructor-note"), Map.of("text", "No"))), ErrorCode.FORBIDDEN);
    }
    @Test void T_03_26_myDogsContainOnlyOwnActiveDogsWithSafeTasksAndLicenses() throws Exception {
        family();
        field("dogs", "dog-two", "licenses", List.of(object("organisation", "EXAMPLE", "number", "123", "category", "M", "grade", "2", "division", "2D")));
        field("dogs", "dog-two", "handlerName", "Example Handler");
        mongo.insert(new Document("_id", "pack").append("clubId", CLUB).append("dogId", "dog-two").append("remaining", 4).append("total", 10), "pack_balances");
        task("pending", "PENDING", null); task("recent", "DONE", clock.instant().minus(Duration.ofDays(30))); task("old", "DONE", clock.instant().minus(Duration.ofDays(31)));
        var response = json(own(get("/api/v1/me/dogs")), 200); assertThat(response.path("dogs")).hasSize(2); assertThat(response.toString()).doesNotContain("chip", "handlerName", "passwordHash", "dog-family");
        assertThat(response.path("dogs").get(0).path("tasks").path("items")).hasSize(2);
        assertThat(response.path("dogs").get(0).path("tasks").path("open").asInt()).isEqualTo(1);
        assertThat(response.path("dogs").get(1).path("pack").path("remaining").asInt()).isEqualTo(4);
        assertThat(response.path("dogs").get(1).path("licenses").get(0).path("division").asText()).isEqualTo("2D");
        assertThat(json(own(get("/api/v1/me/family-group")), 200).toString()).contains("dog-family").doesNotContain("chip");
    }
    void task(String id, String state, Instant done) {
        mongo.insert(new Document("_id", id).append("clubId", CLUB).append("dogId", "dog-one").append("state", state).append("text", "Example task")
                .append("createdAt", Date.from(clock.instant().minus(Duration.ofDays(40)))).append("doneAt", done == null ? null : Date.from(done))
                .append("createdBy", new Document("displayName", "Example Instructor")), "tasks");
    }
    @Test void T_03_27_selfProfileAllowsContactsOnlyAndDoesNotChangeLoginEmail() throws Exception {
        error(own(body(patch("/api/v1/me/profile"), Map.of("firstName", "No"))), ErrorCode.VALIDATION_ERROR);
        own(body(patch("/api/v1/me/profile"), Map.of("firstName", "No"))).andExpect(jsonPath("$.details.fieldErrors[0].code").value("READ_ONLY"));
        error(own(body(patch("/api/v1/me/profile"), Map.of("version", 0, "contactEmails", List.of()))), ErrorCode.VALIDATION_ERROR);
        var response = json(own(body(patch("/api/v1/me/profile"), Map.of("version", 0,
                "contactEmails", List.of(Map.of("email", "contact@example.test"), Map.of("email", "second@example.test")),
                "phones", List.of(Map.of("number", "600 000 005", "label", "Primary"), Map.of("prefix", "+34", "number", "600000006")),
                "address", Map.of("street", "2 Example Street", "postalCode", "08349", "city", "Example Town")))), 200);
        assertThat(response.path("idDocumentMasked").asText()).isEqualTo("12······8Z");
        assertThat(response.path("phones").get(0).path("prefix").asText()).isEqualTo("+34");
        assertThat(mongo.findById("one-account", Account.class).email()).isEqualTo("one@example.test");
        assertThat(events("MemberUpdated")).singleElement().satisfies(event -> assertThat(event.origin()).isEqualTo(DomainEvent.Origin.APP));
    }
    @Test @AuditCovers(AuditAction.FAMILY_GROUP_CHANGED)
    void T_03_28_familyMembershipHolderUpdatesAndDissolutionAreAtomic() throws Exception {
        error(admin(body(post("/api/v1/family-groups"), Map.of("holderMemberId", "one", "memberIds", List.of("one")))), ErrorCode.FAMILY_GROUP_TOO_SMALL);
        String id = family(); assertThat(member("one").familyGroupId).isEqualTo(id);
        error(admin(body(post("/api/v1/family-groups"), Map.of("holderMemberId", "admin", "memberIds", List.of("admin", "one")))), ErrorCode.FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP);
        admin(body(put("/api/v1/family-groups/" + id), Map.of("holderMemberId", "two", "memberIds", List.of("one", "two"), "version", 0))).andExpect(status().isOk());
        assertThat(json(admin(get("/api/v1/members/one")), 200).path("billedViaMemberId").asText()).isEqualTo("two");
        error(admin(body(put("/api/v1/family-groups/" + id), Map.of("holderMemberId", "one", "memberIds", List.of("one", "two"), "version", 0))), ErrorCode.STALE_VERSION);
        admin(delete("/api/v1/family-groups/" + id)).andExpect(status().isNoContent());
        assertThat(member("one").familyGroupId).isNull(); assertThat(member("two").familyGroupId).isNull();
        assertThat(mongo.findById(id, FamilyGroup.class).status).isEqualTo("DISSOLVED"); own(get("/api/v1/me/family-group")).andExpect(status().isNoContent());
        assertThat(audit(AuditAction.FAMILY_GROUP_CHANGED)).hasSize(3);
    }
    @Test void T_03_29_bookingConsumersPersistIndependentSelectionsExactlyOnce() {
        publish("BookingCreated", "Booking", "booking", Map.of("memberId", "one", "dogId", "dog-two", "origin", "BACKOFFICE"));
        dispatcher.dispatch(); long version = member("one").version(); dispatcher.dispatch();
        assertThat(member("one").version()).isEqualTo(version); assertThat(member("one").lastDogForClass).isEqualTo("dog-two"); assertThat(member("one").lastDogForTraining).isNull();
        publish("TrainingBooked", "TrainingBooking", "training", Map.of("memberId", "one", "dogId", "dog-one")); dispatcher.dispatch();
        assertThat(member("one").lastDogForTraining).isEqualTo("dog-one");
        try (var tenant = TenantContext.open(CLUB)) { assertThat(identityAccess.bootstrap("one").lastDogForClass()).isEqualTo("dog-two"); }
    }
    @Test void T_03_30_levelConsumerRecalculatesOnlyNonOverriddenDogs() {
        field("dogs", "dog-two", "freeTrainingOverride", false);
        field("levels", "level-c", "grantsFreeTraining", true);
        publish("LevelChanged", "Level", "level-c", Map.of("id", "level-c", "diff", Map.of("grantsFreeTraining", Map.of("before", false, "after", true))));
        dispatcher.dispatch(); assertThat(events("DogFreeTrainingChanged")).hasSize(2); dispatcher.dispatch(); assertThat(events("DogFreeTrainingChanged")).hasSize(2);
        assertThat(events("DogFreeTrainingChanged")).allSatisfy(event -> assertThat(event.payload().get("source")).isEqualTo("LEVEL"));
    }
    @Test @AuditCovers(AuditAction.MEMBER_STATUS_CHANGED)
    void T_03_06_T_03_31_memberLeaveDeactivatesDogsAndReactivationKeepsTheirStatus() {
        try (var tenant = TenantContext.open(CLUB)) { transactions.run(() -> { statuses.transition("one", "LEFT", LocalDate.of(2026, 1, 1), null); return null; }); }
        dispatcher.dispatch(); assertThat(dog("dog-one").status).isEqualTo("INACTIVE"); assertThat(dog("dog-one").deactivationReason).isEqualTo("MEMBER_LEFT");
        try (var tenant = TenantContext.open(CLUB)) { transactions.run(() -> { statuses.transition("one", "ACTIVE", LocalDate.of(2026, 1, 1), null); return null; }); }
        assertThat(member("one").memberNumber).isEqualTo(1); assertThat(dog("dog-one").status).isEqualTo("INACTIVE"); assertThat(audit(AuditAction.MEMBER_STATUS_CHANGED)).hasSize(2);
    }
    @Test void T_03_32_dogPatchSupportsHandlerAndCompleteLicenseFields() throws Exception {
        error(admin(body(patch("/api/v1/dogs/dog-one"), Map.of("version", 0, "chip", "100002"))), ErrorCode.CHIP_ALREADY_EXISTS);
        error(admin(body(patch("/api/v1/dogs/dog-one"), Map.of("version", 0, "licenses", List.of(Map.of("organisation", "EXAMPLE", "number", "1"), Map.of("organisation", "EXAMPLE", "number", "2"))))), ErrorCode.VALIDATION_ERROR);
        change("dogs/dog-one", Map.of("version", 0, "handlerName", "Example Handler", "licenses", List.of(object("organisation", "EXAMPLE", "number", "1", "category", "S", "grade", "2", "division", "2D"))));
        assertThat(dog("dog-one").handlerName).isEqualTo("Example Handler");
        var response = json(admin(get("/api/v1/dogs").param("filter", "handlerName:contains:Handler")), 200);
        assertThat(response.path("items")).hasSize(1); assertThat(response.path("items").get(0).path("handlerName").asText()).isEqualTo("Example Handler");
        assertThat(response.path("items").get(0).path("licenses").get(0).path("category").asText()).isEqualTo("S");
    }
    @Test void T_03_33_instructorProjectionExcludesFinancialAndInternalFields() throws Exception {
        field("members", "one", "internalNotes", "Private"); field("members", "one", "paymentMethod", Map.of("type", "SEPA_DD", "iban", IBAN));
        field("members", "one", "bookingBlock", Map.of("active", true, "reason", "Private reason"));
        var response = json(call(get("/api/v1/members/one"), CLUB, "one", "INSTRUCTOR"), 200);
        assertThat(response.toString()).doesNotContain("paymentMethod", "nextInvoiceDate", "consents", "internalNotes", "Private", "reason", IBAN);
        assertThat(response.has("contactEmails")).isTrue(); assertThat(response.path("bookingBlocked").asBoolean()).isTrue();
        error(call(body(patch("/api/v1/members/one"), Map.of("version", 0, "firstName", "No")), CLUB, "one", "INSTRUCTOR"), ErrorCode.FORBIDDEN);
        assertThat(json(call(get("/api/v1/dogs/dog-one"), CLUB, "one", "INSTRUCTOR"), 200).path("dog").path("chip").asText()).isEqualTo("100001");
    }
    @Test void T_03_34_moduleBranchesAndDerivedPlanBillingMode() throws Exception {
        mongo.insert(new Document("_id", "plan").append("clubId", CLUB).append("name", new Document("en", "Example Plan")).append("type", "MONTHLY").append("billingMode", "MAINTENANCE"), "plans");
        field("members", "one", "planId", "plan");
        var enabled = json(admin(get("/api/v1/members/one/overview")), 200);
        assertThat(enabled.path("member").path("plan").path("billingMode").asText()).isEqualTo("MAINTENANCE"); assertThat(enabled.path("member").has("billingMode")).isFalse();
        club(CLUB, Set.of()); var disabled = json(admin(get("/api/v1/members/one/overview")), 200);
        assertThat(disabled.toString()).doesNotContain("recentInvoices", "nextInvoice", "paymentMethod", "familyGroup", "pack", "freeTrainingAllowed");
        assertThat(json(own(get("/api/v1/me/dogs")), 200).toString()).doesNotContain("instructorNote", "tasks", "pack", "freeTrainingAllowed");
        for (String path : List.of("/api/v1/family-groups/unknown", "/api/v1/me/family-group")) { error(own(get(path)), ErrorCode.MODULE_DISABLED); }
        error(admin(body(patch("/api/v1/members/one/payment-method"), Map.of("type", "MANUAL"))), ErrorCode.MODULE_DISABLED);
        error(own(body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", "No"))), ErrorCode.MODULE_DISABLED);
    }
    @Test void T_03_36_uniqueIndexesAreScopedToClubAndOptionalChip() throws Exception {
        assertThat(mongo.getCollection("members").listIndexes().into(new ArrayList<>()).toString()).contains("member_number", "member_id_document");
        assertThat(mongo.getCollection("dogs").listIndexes().into(new ArrayList<>()).toString()).contains("dog_chip");
        dog(CLUB, "without-chip", "one", "level-c", null); dog(CLUB, "without-chip-2", "one", "level-c", null);
        error(admin(get("/api/v1/members/foreign")), ErrorCode.NOT_FOUND); error(admin(get("/api/v1/dogs/dog-foreign")), ErrorCode.NOT_FOUND);
        try (var tenant = TenantContext.open(OTHER)) { assertThat(members.findById("one")).isEmpty(); assertThat(dogs.findById("dog-one")).isEmpty(); }
        assertThat(member("foreign").idDocument.get("number")).isEqualTo(member("one").idDocument.get("number"));
    }
    @Test void T_03_37_concurrentPatchesAndBookingBlocksHaveOneWinner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1); var jobs = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) { int value = i; jobs.add(pool.submit(() -> { start.await(); return admin(body(patch("/api/v1/members/one"), Map.of("version", 0, "firstName", "Example " + value))).andReturn().getResponse().getStatus(); })); }
            start.countDown(); assertThat(List.of(jobs.get(0).get(), jobs.get(1).get())).containsExactlyInAnyOrder(200, 409);
            var gate = new CountDownLatch(1); jobs.clear();
            for (int i = 0; i < 2; i++) { jobs.add(pool.submit(() -> { gate.await(); return admin(body(post("/api/v1/members/one/booking-block"), Map.of("reason", "Example"))).andReturn().getResponse().getStatus(); })); }
            gate.countDown(); assertThat(List.of(jobs.get(0).get(), jobs.get(1).get())).containsExactlyInAnyOrder(201, 409);
        }
    }
    @Test void T_03_35_allStaffMutationsEnforceRolesAndTenantIsolation() throws Exception {
        String group = family();
        var requests = List.of(body(patch("/api/v1/members/foreign"), Map.of("version", 0, "firstName", "Example")),
                body(patch("/api/v1/members/foreign/payment-method"), Map.of("type", "MANUAL")),
                body(post("/api/v1/members/foreign/booking-block"), Map.of("reason", "Example")), delete("/api/v1/members/foreign/booking-block"),
                post("/api/v1/members/foreign/access-resend"), body(put("/api/v1/members/foreign/roles"), Map.of("roles", List.of("MEMBER"))),
                body(patch("/api/v1/dogs/dog-foreign"), Map.of("version", 0, "name", "Example")),
                body(patch("/api/v1/dogs/dog-foreign/level"), Map.of("levelId", "level-d")),
                body(patch("/api/v1/dogs/dog-foreign/free-training"), Map.of("override", false)),
                body(post("/api/v1/dogs/dog-foreign/transfer"), Map.of("toMemberId", "one")),
                body(post("/api/v1/dogs/dog-foreign/deactivation"), Map.of()), body(post("/api/v1/dogs/dog-foreign/reactivation"), Map.of()),
                body(put("/api/v1/dogs/dog-foreign/photo"), Map.of("fileKey", "unused")),
                body(post("/api/v1/dogs/dog-foreign/documents"), Map.of("type", "VACCINATION_CARD", "name", "Example", "fileKey", "unused")),
                delete("/api/v1/dogs/dog-foreign/documents/foreign/files/foreign"),
                body(post("/api/v1/dogs/dog-foreign/documents/reminder"), Map.of("type", "VACCINATION_CARD")),
                body(put("/api/v1/family-groups/foreign"), Map.of("holderMemberId", "one", "memberIds", List.of("one", "two"), "version", 0)),
                delete("/api/v1/family-groups/foreign"));
        for (var request : requests) {
            error(own(request), ErrorCode.FORBIDDEN);
            error(call(request, CLUB, "one", "INSTRUCTOR"), ErrorCode.FORBIDDEN);
            error(admin(request), ErrorCode.NOT_FOUND);
        }
        for (String path : List.of("/members/foreign", "/members/foreign/overview", "/dogs/dog-foreign", "/dogs/dog-foreign/documents", "/family-groups/foreign")) {
            error(admin(get("/api/v1" + path)), ErrorCode.NOT_FOUND);
            error(own(get("/api/v1" + path)), ErrorCode.FORBIDDEN);
        }
        for (String path : List.of("/me/profile", "/me/dogs", "/me/family-group")) {
            error(call(get("/api/v1" + path), CLUB, "one", "INSTRUCTOR"), ErrorCode.FORBIDDEN);
            if (path.endsWith("family-group")) { call(get("/api/v1" + path), OTHER, "foreign", "MEMBER").andExpect(status().isNoContent()); }
            else { assertThat(json(call(get("/api/v1" + path), OTHER, "foreign", "MEMBER"), 200).toString()).doesNotContain("dog-one"); }
        }
        assertThat(json(call(get("/api/v1/family-groups/" + group), CLUB, "one", "INSTRUCTOR"), 200).path("memberIds")).hasSize(2);
        error(own(body(post("/api/v1/family-groups"), Map.of("holderMemberId", "one", "memberIds", List.of("one", "two")))), ErrorCode.FORBIDDEN);
    }

    @Test void T_03_23_photoAndNoteAttachmentsAreBoundPrivateAndReplaySafe() throws Exception {
        byte[] data = "Example image bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var photo = uploadUrl("DOG_PHOTO", "image/png", data, false);
        var input = Map.of("fileKey", photo.path("fileKey").asText());
        String url = json(own(body(put("/api/v1/me/dogs/dog-one/photo"), input)), 200).path("photoUrl").asText();
        own(get(url)).andExpect(status().isOk()).andExpect(content().bytes(data));
        own(body(put("/api/v1/me/dogs/dog-one/photo"), input)).andExpect(status().isOk());
        assertThat(events("DogUpdated")).hasSize(1);
        error(own(body(put("/api/v1/me/dogs/dog-two/photo"), input)), ErrorCode.ATTACHMENT_ENTITY_MISMATCH);
        error(admin(body(put("/api/v1/dogs/dog-one/photo"), input)), ErrorCode.ATTACHMENT_ENTITY_MISMATCH);
        error(call(get(url), OTHER, "foreign", "MEMBER"), ErrorCode.NOT_FOUND);
        error(own(get(url + "x")), ErrorCode.FORBIDDEN);
        error(own(put(photo.path("uploadUrl").asText()).contentType("image/png").content(data)), ErrorCode.INVALID_STATE);
        var adminPhoto = uploadUrl("DOG_PHOTO", "image/png", data, true);
        admin(body(put("/api/v1/dogs/dog-one/photo"), Map.of("fileKey", adminPhoto.path("fileKey").asText()))).andExpect(status().isOk());
        own(body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", "Example note"))).andExpect(status().isOk());
        var file = uploadUrl("INSTRUCTOR_NOTE", "application/pdf", data, false);
        var note = Map.of("entityType", "INSTRUCTOR_NOTE", "entityId", "dog-one", "fileKey", file.path("fileKey").asText(), "name", "Example.pdf");
        var saved = json(own(body(post("/api/v1/attachments"), note)), 201);
        int auditCount = audit(AuditAction.DOG_UPDATED).size();
        assertThat(json(own(body(post("/api/v1/attachments"), note)), 201)).isEqualTo(saved);
        assertThat(events("AttachmentAdded")).hasSize(1); assertThat(audit(AuditAction.DOG_UPDATED)).hasSize(auditCount);
        assertThat(json(own(get("/api/v1/me/dogs")), 200).path("dogs").get(0).path("instructorNote").path("attachments")).hasSize(1);
        error(admin(body(post("/api/v1/attachments"), note)), ErrorCode.FORBIDDEN);
        error(own(body(post("/api/v1/attachments"), Map.of("entityType", "TASK", "entityId", "dog-one", "fileKey", "unused", "name", "Example"))), ErrorCode.ATTACHMENT_ENTITY_MISMATCH);
        parameter("files.maxAttachmentsPerEntity", 1);
        var second = uploadUrl("INSTRUCTOR_NOTE", "application/pdf", data, false);
        error(own(body(post("/api/v1/attachments"), Map.of("entityType", "INSTRUCTOR_NOTE", "entityId", "dog-one", "fileKey", second.path("fileKey").asText(), "name", "Example"))), ErrorCode.ATTACHMENT_LIMIT_REACHED);
        clock.advance(Duration.ofMinutes(6)); error(own(get(url)), ErrorCode.FORBIDDEN);
    }

    @Test void T_03_17_impersonatedProfileEditsRecordTheRealActor() throws Exception {
        String token;
        try (var tenant = TenantContext.open(CLUB)) { token = impersonation.create("admin-account", "one", "Example support").token().getTokenValue(); }
        mvc.perform(body(patch("/api/v1/me/profile"), Map.of("version", 0, "phones", List.of(Map.of("number", "600000099"))))
                .header("Host", CLUB + ".example.test").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(events("MemberUpdated")).singleElement().satisfies(event -> {
            assertThat(event.actorAccountId()).isEqualTo("admin-account"); assertThat(event.impersonatedMemberId()).isEqualTo("one");
            assertThat(event.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
        });
        assertThat(audit(AuditAction.MEMBER_UPDATED)).singleElement().satisfies(entry -> {
            assertThat(entry.actorAccountId()).isEqualTo("admin-account"); assertThat(entry.impersonatedMemberId()).isEqualTo("one");
        });
        mvc.perform(body(patch("/api/v1/members/one"), Map.of("version", 1, "firstName", "Forbidden"))
                .header("Host", CLUB + ".example.test").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test void T_03_34_curlSeededMemberOverviewThroughTheRealHttpServer() throws Exception {
        String password = UUID.randomUUID().toString(); field("accounts", "admin-account", "passwordHash", passwords.hash(password));
        field("memberships", "admin-membership", "defaultProfile", "ADMIN");
        String bearer;
        try (var tenant = TenantContext.open(CLUB)) { bearer = tokens.password("admin@example.test", password, "clubs-admin").access().getTokenValue(); }
        var config = java.nio.file.Files.createTempFile("census-curl-", ".conf", java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        try {
            java.nio.file.Files.writeString(config, "url = \"http://127.0.0.1:" + port + "/api/v1/members/one/overview\"\nheader = \"Host: " + CLUB + ".example.test\"\nheader = \"Authorization: Bearer " + bearer + "\"\n");
            var process = new ProcessBuilder("curl", "--silent", "--show-error", "--fail-with-body", "--config", config.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
            var response = mapper.readTree(output); assertThat(response.path("member").path("id").asText()).isEqualTo("one");
            assertThat(response.path("dogs")).hasSize(2); assertThat(output).doesNotContain("passwordHash", bearer);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/census-overview-curl.txt"), "curl --silent --show-error --fail-with-body --config <private temporary config>\nGET http://127.0.0.1:" + port + "/api/v1/members/one/overview\nHost: " + CLUB + ".example.test\nAuthorization: Bearer eyJ…[truncated]\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response) + "\nExit status: 0\n");
        } finally { java.nio.file.Files.deleteIfExists(config); }
    }

    @Test void T_03_12_fullMemberEditsValidateDatesAndPreserveCalendarDates() throws Exception {
        var update = object("version", 0, "idDocument", Map.of("type", "DNI", "number", "11111111H"), "lastName1", "Updated", "lastName2", "Example",
                "gender", "FEMALE", "birthDate", "1992-08-15", "remarks", "Example remarks", "internalNotes", "Example internal notes",
                "consents", Map.of("imageRights", Map.of("granted", false)));
        change("members/one", update);
        assertThat(member("one").birthDate).isEqualTo(LocalDate.of(1992, 8, 15));
        assertThat(mongo.findById("one", Document.class, "members").get("birthDate")).isEqualTo("1992-08-15");
        assertThat(member("one").consents).containsKey("privacyPolicy");
        assertThat(json(admin(get("/api/v1/members/one")), 200).path("consents").path("imageRights").path("granted").asBoolean()).isFalse();
        assertThat(mapper.writeValueAsString(audit(AuditAction.MEMBER_UPDATED))).doesNotContain("11111111H");
        for (var field : List.of(object("gender", "INVALID"), object("birthDate", "bad"), object("birthDate", "2999-01-01"),
                object("consents", Map.of("imageRights", Map.of())), object("billingMode", "MONTHLY_FEE"), object("firstName", ""), object("phones", List.of(42)))) {
            field.put("version", 1); error(admin(body(patch("/api/v1/members/one"), field)), ErrorCode.VALIDATION_ERROR);
        }
        var clear = object("version", 1); clear.put("lastName2", null); clear.put("remarks", null); clear.put("internalNotes", null);
        change("members/one", clear); assertThat(member("one").lastName2).isNull();
        for (var invalid : List.of(object("type", "OTHER"), object("type", "MANUAL", "manual", Map.of("channel", "unknown")),
                object("type", "SEPA_DD", "sepa", Map.of("holderName", "Example", "holderTaxId", "bad")))) {
            error(admin(body(patch("/api/v1/members/one/payment-method"), invalid)), ErrorCode.VALIDATION_ERROR);
        }
        change("members/one/payment-method", object("type", "SEPA_DD", "sepa", Map.of("holderName", "Example", "holderTaxId", "X1234567L", "iban", IBAN)));
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow());
        node.set("paymentProviders", mapper.valueToTree(Map.of("STRIPE", Map.of("enabled", true)))); clubs.save(mapper.convertValue(node, Club.class)); configs.invalidate(CLUB);
        error(admin(body(patch("/api/v1/members/one/payment-method"), Map.of("type", "CARD", "card", Map.of("stripeSetupIntentId", "unconfirmed")))), ErrorCode.NOT_IMPLEMENTED);
    }

    @Test void T_03_32_dogEditsValidateStateHistoryAndOptionalFields() throws Exception {
        for (var input : List.of(object("sex", "INVALID"), object("birthDate", "bad"), object("birthDate", "2999-01-01"),
                object("handlerName", "x".repeat(81)), object("licenses", "bad"), object("memberId", "two"))) {
            input.put("version", 0); error(admin(body(patch("/api/v1/dogs/dog-one"), input)), ErrorCode.VALIDATION_ERROR);
        }
        change("dogs/dog-one", object("version", 0, "name", "Example new name", "breed", "Example new breed", "sex", "MALE", "birthDate", "2022-08-15", "chip", "", "handlerName", ""));
        assertThat(dog("dog-one").chip).isNull(); assertThat(dog("dog-one").handlerName).isNull();
        assertThat(mongo.findById("dog-one", Document.class, "dogs").get("birthDate")).isEqualTo("2022-08-15");
        change("dogs/dog-one", Map.of("version", 1, "name", "Example new name")); assertThat(dog("dog-one").version()).isEqualTo(1);
        error(admin(body(patch("/api/v1/dogs/dog-one/free-training"), Map.of())), ErrorCode.VALIDATION_ERROR);
        error(admin(body(patch("/api/v1/dogs/dog-one/free-training"), Map.of("override", "yes"))), ErrorCode.VALIDATION_ERROR);
        change("dogs/dog-one/free-training", Map.of("override", false)); change("dogs/dog-one/free-training", Map.of("override", false));
        error(admin(body(patch("/api/v1/dogs/dog-one/level"), Map.of("levelId", "foreign-level"))), ErrorCode.LEVEL_NOT_ACTIVE);
        field("dogs", "dog-one", "levelHistory", List.of()); field("dogs", "dog-one", "levelAssignedAt", null);
        change("dogs/dog-one/level", Map.of("levelId", "level-d")); assertThat(dog("dog-one").levelHistory).hasSize(2);
        error(admin(body(post("/api/v1/dogs/dog-one/reactivation"), Map.of())), ErrorCode.DOG_NOT_ACTIVE);
        error(admin(body(post("/api/v1/dogs/dog-one/transfer"), Map.of("toMemberId", "one"))), ErrorCode.SAME_MEMBER);
        admin(body(post("/api/v1/dogs/dog-one/deactivation"), Map.of())).andExpect(status().isOk());
        error(admin(body(post("/api/v1/dogs/dog-one/deactivation"), Map.of())), ErrorCode.DOG_NOT_ACTIVE);
        field("levels", "level-d", "active", false);
        error(admin(body(post("/api/v1/dogs/dog-one/reactivation"), Map.of())), ErrorCode.LEVEL_NOT_ACTIVE);
        parameter("levels.enabled", false); admin(body(post("/api/v1/dogs/dog-one/reactivation"), Map.of())).andExpect(status().isOk());
        assertThat(json(admin(get("/api/v1/dogs/dog-one")), 200).toString()).doesNotContain("levelHistory", "levelAssignedAt");
    }

    @Test void T_03_28_T_03_15_familyAndBookingEligibilityRejectInvalidAndUnrelatedMembers() throws Exception {
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> eligibility.check("two", "dog-one")).hasMessage("FORBIDDEN");
            field("members", "one", "status", "LEFT"); assertThatThrownBy(() -> eligibility.check("one", "dog-one")).hasMessage("MEMBER_NOT_ACTIVE");
            field("members", "one", "status", "ACTIVE"); field("dogs", "dog-one", "status", "INACTIVE");
            assertThatThrownBy(() -> eligibility.check("one", "dog-one")).hasMessage("DOG_NOT_ACTIVE"); field("dogs", "dog-one", "status", "ACTIVE");
        }
        error(admin(body(post("/api/v1/family-groups"), Map.of("holderMemberId", "admin", "memberIds", List.of("one", "two")))), ErrorCode.VALIDATION_ERROR);
        error(admin(body(post("/api/v1/family-groups"), Map.of("holderMemberId", "one", "memberIds", List.of("one", "one", "two")))), ErrorCode.VALIDATION_ERROR);
        field("members", "two", "status", "PENDING");
        error(admin(body(post("/api/v1/family-groups"), Map.of("holderMemberId", "one", "memberIds", List.of("one", "two")))), ErrorCode.MEMBER_NOT_ACTIVE);
        field("members", "two", "status", "ACTIVE"); String id = family();
        admin(body(put("/api/v1/family-groups/" + id), Map.of("holderMemberId", "one", "memberIds", List.of("one", "admin"), "version", 0))).andExpect(status().isOk());
        assertThat(member("two").familyGroupId).isNull(); assertThat(member("admin").familyGroupId).isEqualTo(id);
        admin(delete("/api/v1/family-groups/" + id)).andExpect(status().isNoContent()); admin(delete("/api/v1/family-groups/" + id)).andExpect(status().isNoContent());
        error(admin(body(put("/api/v1/family-groups/" + id), Map.of("holderMemberId", "one", "memberIds", List.of("one", "two"), "version", 2))), ErrorCode.INVALID_STATE);
        field("members", "one", "familyGroupId", id); own(get("/api/v1/me/family-group")).andExpect(status().isNoContent());
        assertThat(json(admin(get("/api/v1/members/one")), 200).path("billedViaMemberId").asText()).isEqualTo("one");
    }

    @Test void T_03_29_T_03_30_T_03_31_consumersIgnoreObsoleteEventsAndHandleRegistrationAndRejection() throws Exception {
        publish("DogRegistered", "Dog", "dog-one", Map.of("dogId", "dog-one")); dispatcher.dispatch();
        publish("DogRegistered", "Dog", "dog-one", Map.of("dogId", "dog-one")); dispatcher.dispatch();
        assertThat(events("DogDocumentPending")).hasSize(1);
        for (String target : List.of("missing", "one")) {
            publish("SignupRejected", "Member", target, Map.of("memberId", target)); dispatcher.dispatch();
        }
        assertThat(dog("dog-one").status).isEqualTo("ACTIVE");
        field("members", "one", "status", "PENDING"); field("dogs", "dog-one", "status", "PENDING");
        publish("SignupRejected", "Member", "one", Map.of("memberId", "one")); dispatcher.dispatch();
        assertThat(dog("dog-one").deactivationReason).isEqualTo("SIGNUP_REJECTED");
        for (var payload : List.of(Map.of("memberId", "one", "after", "ACTIVE"), Map.of("memberId", "one", "after", "LEFT", "effectiveDate", "2999-01-01"),
                Map.of("memberId", "missing", "after", "LEFT"), Map.of("memberId", "one", "after", "LEFT"))) {
            publish("MemberStatusChanged", "Member", "one", new HashMap<>(payload)); dispatcher.dispatch();
        }
        assertThat(dog("dog-two").status).isEqualTo("ACTIVE");
        field("members", "one", "status", "ACTIVE");
        for (var payload : List.of(Map.of("memberId", "missing", "dogId", "dog-two"), Map.of("memberId", "one", "dogId", "missing"),
                Map.of("memberId", "one", "dogId", "dog-one"), Map.of("memberId", "two", "dogId", "dog-two"))) {
            publish("BookingCreated", "Booking", UUID.randomUUID().toString(), new HashMap<>(payload)); dispatcher.dispatch();
        }
        assertThat(member("one").lastDogForClass).isNull(); assertThat(member("two").lastDogForClass).isNull();
        family(); publish("BookingCreated", "Booking", "family-booking", Map.of("memberId", "two", "dogId", "dog-two")); dispatcher.dispatch();
        assertThat(member("two").lastDogForClass).isEqualTo("dog-two");
        publish("BookingCreated", "Booking", "same-dog", Map.of("memberId", "two", "dogId", "dog-two")); dispatcher.dispatch();
        field("members", "two", "erasedAt", clock.instant());
        publish("TrainingBooked", "TrainingBooking", "erased-booking", Map.of("memberId", "two", "dogId", "dog-family")); dispatcher.dispatch();
        assertThat(member("two").lastDogForTraining).isNull();
        publish("LevelChanged", "Level", "level-c", Map.of("id", "level-c", "diff", Map.of("name", Map.of("before", "Old", "after", "New")))); dispatcher.dispatch();
        publish("LevelChanged", "Level", "level-c", Map.of("id", "level-c", "diff", Map.of("active", Map.of("before", true, "after", false)))); dispatcher.dispatch();
        assertThat(events("DogFreeTrainingChanged")).isEmpty();
        club(CLUB, Set.of()); publish("TrainingBooked", "TrainingBooking", "disabled", Map.of("memberId", "one", "dogId", "dog-two")); dispatcher.dispatch();
        assertThat(member("one").lastDogForTraining).isNull();
    }

    @Test void T_03_28_T_03_23_concurrentFamilyGroupsAndAttachmentLimitsAreAtomic() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1); var results = new ArrayList<Future<Integer>>();
            for (String other : List.of("two", "admin")) {
                results.add(pool.submit(() -> { gate.await(); return admin(body(post("/api/v1/family-groups"), Map.of("holderMemberId", "one", "memberIds", List.of("one", other)))).andReturn().getResponse().getStatus(); }));
            }
            gate.countDown(); assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(201, 409);
            assertThat(mongo.getCollection("family_groups").countDocuments()).isEqualTo(1);
            parameter("files.maxAttachmentsPerEntity", 1);
            var first = uploadUrl("INSTRUCTOR_NOTE", "text/plain", "first".getBytes(), false);
            var second = uploadUrl("INSTRUCTOR_NOTE", "text/plain", "second".getBytes(), false);
            var start = new CountDownLatch(1); results.clear();
            for (var upload : List.of(first, second)) {
                results.add(pool.submit(() -> { start.await(); return own(body(post("/api/v1/attachments"), Map.of("entityType", "INSTRUCTOR_NOTE", "entityId", "dog-one",
                        "fileKey", upload.path("fileKey").asText(), "name", "Example"))).andReturn().getResponse().getStatus(); }));
            }
            start.countDown(); assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(201, ErrorCode.ATTACHMENT_LIMIT_REACHED.httpStatus());
            assertThat(mongo.getCollection("attachments").countDocuments()).isEqualTo(1); assertThat(events("AttachmentAdded")).hasSize(1);
        }
    }

    @Test void T_03_35_ownerMutationsRejectForeignDogsAndStaffProfiles() throws Exception {
        for (var request : List.of(body(put("/api/v1/me/dogs/dog-foreign/photo"), Map.of("fileKey", "unused")),
                body(post("/api/v1/me/dogs/dog-foreign/documents"), Map.of("type", "VACCINATION_CARD", "name", "Example", "fileKey", "unused")),
                body(put("/api/v1/me/dogs/dog-foreign/instructor-note"), Map.of("text", "Example")),
                body(post("/api/v1/attachments"), Map.of("entityType", "INSTRUCTOR_NOTE", "entityId", "dog-foreign", "fileKey", "unused", "name", "Example")))) {
            error(own(request), ErrorCode.NOT_FOUND); error(call(request, CLUB, "one", "INSTRUCTOR"), ErrorCode.FORBIDDEN);
        }
        error(call(body(post("/api/v1/attachments/upload-url"), Map.of("purpose", "DOG_PHOTO", "fileName", "Example", "mimeType", "image/png", "sizeBytes", 4)), CLUB, "one", "INSTRUCTOR"), ErrorCode.FORBIDDEN);
    }

    @Test void T_03_35_erasedMembersRejectEveryMutationIncludingOwnedDogsAndGroups() throws Exception {
        String group = family(); field("members", "one", "erasedAt", clock.instant());
        for (var request : List.of(body(patch("/api/v1/members/one"), Map.of("version", 1, "firstName", "No")),
                body(patch("/api/v1/members/one/payment-method"), Map.of("type", "MANUAL")),
                body(post("/api/v1/members/one/booking-block"), Map.of("reason", "No")), delete("/api/v1/members/one/booking-block"),
                post("/api/v1/members/one/access-resend"), body(put("/api/v1/members/one/roles"), Map.of("roles", List.of("MEMBER"))),
                body(patch("/api/v1/dogs/dog-one"), Map.of("version", 0, "name", "No")), body(patch("/api/v1/dogs/dog-one/level"), Map.of("levelId", "level-d")),
                body(patch("/api/v1/dogs/dog-one/free-training"), Map.of("override", true)), body(post("/api/v1/dogs/dog-one/transfer"), Map.of("toMemberId", "two")),
                body(post("/api/v1/dogs/dog-one/deactivation"), Map.of()), body(post("/api/v1/dogs/dog-one/reactivation"), Map.of()),
                body(put("/api/v1/dogs/dog-one/photo"), Map.of("fileKey", "unused")), body(post("/api/v1/dogs/dog-one/documents"), Map.of("type", "VACCINATION_CARD", "name", "No", "fileKey", "unused")),
                body(post("/api/v1/dogs/dog-one/documents/reminder"), Map.of("type", "VACCINATION_CARD")), delete("/api/v1/family-groups/" + group))) {
            error(admin(request), ErrorCode.MEMBER_ERASED);
        }
        error(own(body(patch("/api/v1/me/profile"), Map.of("version", 1, "phones", List.of(Map.of("number", "600000001"))))), ErrorCode.MEMBER_ERASED);
        error(own(body(put("/api/v1/me/dogs/dog-one/instructor-note"), Map.of("text", "No"))), ErrorCode.MEMBER_ERASED);
    }
}
