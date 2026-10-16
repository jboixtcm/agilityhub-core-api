package com.agilityhub.core.platform.privacy;

import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Dog;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Fictional clubs, accounts, members and dogs of the S14 RGPD integration tests (E11-T01). Every person is `@example.test`. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
abstract class PrivacyItFixtures extends AbstractIntegrationTest {
    static final String CLUB = "rgpd-a", OTHER = "rgpd-b";
    static final String IBAN = "ES9121000418450200051332";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired CensusRepository<Member> members; @Autowired CensusRepository<Dog> dogs;

    void club(String id) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id + ".example.test"));
        node.set("modules", mapper.valueToTree(Set.of(Module.values())));
        node.set("paymentProviders", mapper.valueToTree(Map.of("SEPA_XML", Map.of("enabled", true), "MANUAL", Map.of("enabled", true))));
        clubs.save(mapper.convertValue(node, Club.class)); configs.invalidate(id); hosts.invalidate();
    }
    void account(String id, String email) {
        mongo.insert(new Account(id, email, "Fictional " + id, "ca", null, Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false,
                Instant.parse("2020-01-01T00:00:00Z")));
    }
    void membership(String accountId, String club, String memberId, Membership.Status status, Role... roles) {
        mongo.insert(new Membership(accountId + "-" + club, accountId, club, memberId, Set.of(roles.length == 0 ? new Role[] {Role.MEMBER} : roles),
                status, roles.length == 0 ? Role.MEMBER : roles[0]));
    }
    /** A member and its account and membership; `leaveDate` set → `LEFT`. */
    Member member(String club, String id, int number, String document, LocalDate leaveDate) {
        account(id + "-account", id + "@example.test");
        membership(id + "-account", club, id, leaveDate == null ? Membership.Status.ACTIVE : Membership.Status.SUSPENDED);
        var member = new Member(); member.id = id; member.clubId = club; member.accountId = id + "-account"; member.memberNumber = number;
        member.idDocument = map("type", "DNI", "number", document);
        member.firstName = "Fictional"; member.lastName1 = "Person " + id; member.lastName2 = "Second"; member.gender = "FEMALE"; member.birthDate = LocalDate.of(1990, 5, 6);
        member.contactEmails = List.of(map("email", id + "@example.test", "bounced", false));
        member.phones = List.of(map("prefix", "+34", "number", "600000001", "label", "Primary"));
        member.address = map("street", "1 Fictional Street", "postalCode", "08349", "city", "Fictional Town", "country", "ES");
        member.paymentMethod = map("type", "SEPA_DD", "iban", IBAN, "holderName", "Fictional Holder", "holderTaxId", "12345678Z", "mandateRef", "rgpd-" + number + "-1",
                "mandateSignedAt", "2020-01-01T00:00:00Z");
        member.remarks = "Remarks about " + id; member.internalNotes = "Internal note about " + id;
        member.signup = map("submittedAt", Instant.parse("2020-01-01T00:00:00Z"), "locale", "ca", "userAgent", "Fictional Browser", "ipHash", "hash-" + id);
        member.familyGroupClaim = map("status", "NOT_FOUND_PENDING", "holderName", "Fictional Holder", "dogName", "Fictional Dog");
        member.bookingBlock = map("active", false, "reason", "a fictional reason");
        member.notificationPreferences = map("pushClubNews", false);
        member.status = leaveDate == null ? "ACTIVE" : "LEFT"; member.joinedAt = Instant.parse("2020-01-01T00:00:00Z");
        member.leaveDate = leaveDate; member.leftAt = leaveDate == null ? null : leaveDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        member.leftReason = leaveDate == null ? null : "LEAVE_REQUEST";
        member.planId = "plan-a"; member.priceId = "price-a";
        member.consents = map("history", List.of(map("type", "PRIVACY_POLICY", "granted", true, "version", "v1", "acceptedAt", Instant.parse("2020-01-01T00:00:00Z"),
                "locale", "ca", "ipHash", "hash-" + id, "source", "SIGNUP"), map("type", "IMAGE_USE", "granted", false, "version", "v1",
                "acceptedAt", Instant.parse("2020-01-01T00:00:01Z"), "locale", "ca", "ipHash", "hash-" + id, "source", "SIGNUP")));
        try (var tenant = TenantContext.open(club)) { return members.insert(member); }
    }
    Dog dog(String club, String id, String member, String chip, String photoKey) {
        var dog = new Dog(); dog.id = id; dog.clubId = club; dog.memberId = member; dog.name = "Fictional " + id; dog.breed = "whippet"; dog.sex = "FEMALE";
        dog.birthDate = LocalDate.of(2021, 3, 12); dog.chip = chip; dog.status = "ACTIVE"; dog.levelId = "level-a"; dog.registeredAt = Instant.parse("2021-05-01T00:00:00Z");
        dog.levelHistory = List.of(map("levelId", "level-a", "from", "2021-05-01T00:00:00Z")); dog.handlerName = "Fictional Handler";
        dog.licenses = List.of(map("organisation", "RSCE", "number", "L-1")); dog.instructorNote = map("text", "Instructor note about " + id);
        dog.remarks = "Dog remarks of " + id; dog.remarksMeta = map("updatedByName", "Fictional Instructor"); dog.photoFileKey = photoKey;
        try (var tenant = TenantContext.open(club)) { return dogs.insert(dog); }
    }
    void raw(String collection, Object... keyValues) { mongo.insert(new Document(map(keyValues)), collection); }
    Document document(String collection, String id) { return mongo.findById(id, Document.class, collection); }
    List<Document> documents(String collection, String field, Object value) { return mongo.find(Query.query(Criteria.where(field).is(value)), Document.class, collection); }
    static Map<String, Object> map(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) { map.put((String) keyValues[i], keyValues[i + 1] instanceof Instant instant ? Date.from(instant) : keyValues[i + 1]); }
        return map;
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String account, String... roles) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject(account).claim("clubId", club).claim("name", "Fictional Operator")
                .claim("locale", "ca")).authorities(Arrays.stream(roles).<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "admin-account", "ADMIN"); }
    ResultActions platform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Host", "unknown.example.test").with(jwt().jwt(j -> j.subject("platform-account"))
                .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))));
    }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType("application/json").content(mapper.writeValueAsString(body));
    }
    JsonNode json(ResultActions result, int status) throws Exception {
        var response = result.andReturn().getResponse();
        org.assertj.core.api.Assertions.assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isEmpty() ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }
    void seedClubs() {
        wipeDatabaseKeepingBootstrap();
        club(CLUB); club(OTHER);
        account("admin-account", "admin@example.test");
        membership("admin-account", CLUB, null, Membership.Status.ACTIVE, Role.ADMIN);
        account("other-admin-account", "other-admin@example.test");
        membership("other-admin-account", OTHER, null, Membership.Status.ACTIVE, Role.ADMIN);
    }
}
