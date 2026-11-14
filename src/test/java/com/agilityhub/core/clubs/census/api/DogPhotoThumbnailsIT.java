package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.application.DocumentService;
import com.agilityhub.core.clubs.census.application.PhotoThumbnailsCommand;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.clubs.followup.application.FollowupErasure;
import com.agilityhub.core.clubs.followup.application.PhotoThumbnails;
import com.agilityhub.core.clubs.followup.persistence.UploadGrant;
import com.agilityhub.core.clubs.census.application.CensusErasure;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.ErasurePlaceholders;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.TestPictures;
import com.fasterxml.jackson.databind.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.agilityhub.core.clubs.census.application.CensusValues.object;

/**
 * WEB-05 (owner's decision of 13 Nov 2026): a claimed or migrated dog photo gets a small upright JPEG rendition stored next to it
 * (filesystem store here; {@code DogPhotoThumbnailsS3IT} for S3), which the lists sign beside the photo's URL; a replaced or erased
 * photo's rendition goes, and `census:photo-thumbnails` makes the missing ones once. Every picture is drawn in the test.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class DogPhotoThumbnailsIT extends AbstractIntegrationTest {
    static final String CLUB = "thumbs-a", OTHER = "thumbs-b";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired CensusRepository<Member> members; @Autowired CensusRepository<Dog> dogs;
    @Autowired AttachmentStorage storage; @Autowired AttachmentService attachments; @Autowired DocumentService documents;
    @Autowired CensusErasure censusErasure; @Autowired FollowupErasure followupErasure; @Autowired PhotoThumbnailsCommand command;

    @BeforeEach void seed() {
        TenantContext.clear();
        for (String collection : List.of("parameters", "memberships", "members", "dogs", "dog_documents", "levels", "attachments", "attachment_uploads",
                "audit_entries", "domain_events", "census_write_locks", "tasks", "followup_items")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        mongo.remove(Query.query(Criteria.where("_id").regex("^thumbs-")), "accounts");
        club(CLUB); club(OTHER);
        member(CLUB, "one", 1, Set.of(Role.MEMBER)); member(CLUB, "admin", 2, Set.of(Role.MEMBER, Role.ADMIN));
        member(OTHER, "foreign", 1, Set.of(Role.MEMBER, Role.ADMIN));
        dog(CLUB, "dog-one", "one"); dog(CLUB, "dog-two", "one"); dog(CLUB, "dog-three", "admin"); dog(OTHER, "dog-foreign", "foreign");
    }
    void club(String id) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(clubs.findById(id).orElseGet(() -> PlatformFixtures.club(id, id + ".example.test")));
        node.set("modules", mapper.valueToTree(Set.of(Module.values())));
        clubs.save(mapper.convertValue(node, Club.class)); configs.invalidate(id); hosts.invalidate();
    }
    void member(String club, String id, int number, Set<Role> roles) {
        String account = "thumbs-" + club + "-" + id;
        mongo.save(new Account(account, account + "@example.test", "Example " + id, "en", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
        mongo.save(new Membership(account, account, club, id, roles, Membership.Status.ACTIVE, Role.MEMBER));
        var member = new Member(); member.id = club + "-" + id; member.clubId = club; member.accountId = account; member.memberNumber = number;
        member.idDocument = object("type", "DNI", "number", "0000000" + number + "T"); member.firstName = "Example"; member.lastName1 = id;
        member.gender = "OTHER"; member.birthDate = LocalDate.of(1990, 1, 1); member.status = "ACTIVE"; member.joinedAt = Instant.parse("2020-01-01T00:00:00Z");
        member.contactEmails = List.of(object("email", account + "@example.test", "bounced", false)); member.phones = List.of();
        member.address = object("street", "1 Example Street", "postalCode", "08349", "city", "Example Town", "country", "ES");
        member.bookingBlock = object("active", false); member.consents = object("imageRights", object("granted", true));
        try (var tenant = TenantContext.open(club)) { members.insert(member); }
    }
    void dog(String club, String id, String member) {
        var dog = new Dog(); dog.id = id; dog.clubId = club; dog.memberId = club + "-" + member; dog.name = "Example " + id; dog.breed = "Example breed";
        dog.sex = "FEMALE"; dog.birthDate = LocalDate.of(2022, 3, 12); dog.status = "ACTIVE"; dog.registeredAt = Instant.parse("2023-01-01T00:00:00Z");
        dog.licenses = List.of();
        try (var tenant = TenantContext.open(club)) { dogs.insert(dog); }
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String member, String... roles) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject("thumbs-" + club + "-" + member).claim("clubId", club)
                .claim("name", "Example Operator").claim("locale", "en").claim("auth_time", signedInNow())).authorities(Arrays.stream(roles)
                .<org.springframework.security.core.GrantedAuthority>map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)).toList())));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "admin", "ADMIN"); }
    ResultActions instructor(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "admin", "INSTRUCTOR"); }
    ResultActions own(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "one", "MEMBER"); }
    JsonNode json(ResultActions result, int status) throws Exception { return mapper.readTree(result.andExpect(status().is(status)).andReturn().getResponse().getContentAsString()); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception { return request.contentType("application/json").content(mapper.writeValueAsString(body)); }

    final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
    /** The photo flow of 13 and of the dog's record: upload grant, signed PUT (the api's route, or S3's presigned URL), then the claim. */
    String claim(boolean member, String dogId, String type, byte[] bytes) throws Exception {
        var grant = json(member ? own(body(post("/api/v1/attachments/upload-url"), Map.of("purpose", "DOG_PHOTO", "fileName", "photo", "mimeType", type, "sizeBytes", bytes.length)))
                : admin(body(post("/api/v1/attachments/upload-url"), Map.of("purpose", "DOG_PHOTO", "fileName", "photo", "mimeType", type, "sizeBytes", bytes.length))), 201);
        String uploadUrl = grant.path("uploadUrl").asText();
        if (uploadUrl.startsWith("http")) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(uploadUrl)).PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes));
            grant.path("headers").fields().forEachRemaining(header -> request.header(header.getKey(), header.getValue().asText()));
            assertThat(http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        } else {
            var upload = put(uploadUrl).contentType(type).content(bytes);
            (member ? own(upload) : admin(upload)).andExpect(status().isNoContent());
        }
        String key = grant.path("fileKey").asText();
        var path = member ? "/api/v1/me/dogs/" + dogId + "/photo" : "/api/v1/dogs/" + dogId + "/photo";
        var answer = json(member ? own(body(put(path), Map.of("fileKey", key))) : admin(body(put(path), Map.of("fileKey", key))), 200);
        assertThat(answer.path("photoUrl").asText()).isNotBlank();
        return key;
    }
    Document stored(String dogId) { return mongo.findById(dogId, Document.class, "dogs"); }
    String thumbnailOf(String dogId) { return stored(dogId).getString("photoThumbnailFileKey"); }
    Document grant(String key) { return mongo.findById(key, Document.class, "attachment_uploads"); }
    boolean exists(String key) { try { storage.metadata(key); return true; } catch (ApiException missing) { return false; } }
    /** A signed URL's bytes: the api's local route, or S3's presigned URL (absolute). */
    byte[] fetch(String url) throws Exception { return download(url, null); }
    BufferedImage picture(String url) throws Exception { return ImageIO.read(new ByteArrayInputStream(download(url, "image/jpeg"))); }
    byte[] download(String url, String type) throws Exception {
        if (url.startsWith("http")) {
            var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            if (type != null) { assertThat(response.headers().firstValue("Content-Type")).hasValue(type); }
            return response.body();
        }
        var response = admin(get(url)).andExpect(status().isOk()).andReturn().getResponse();
        if (type != null) { assertThat(response.getContentType()).isEqualTo(type); }
        return response.getContentAsByteArray();
    }
    static JsonNode dogIn(JsonNode dogs, String id) {
        return java.util.stream.StreamSupport.stream(dogs.spliterator(), false).filter(d -> d.path("id").asText().equals(id)).findFirst().orElseThrow();
    }
    /** A stored landscape picture with a red band along its row 0, shown upright as a portrait (EXIF orientation 6: row 0 on the right). */
    static byte[] cameraJpeg() throws Exception {
        var stored = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        var graphics = stored.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 640, 480); graphics.setColor(Color.RED); graphics.fillRect(0, 0, 640, 60); graphics.dispose();
        return TestPictures.withExif(TestPictures.jpeg(stored, false), 6, false);
    }

    @Test void WEB_05_T_03_23_aClaimedPhotoGetsAnUprightThumbnailThatEveryListSignsBesideTheOriginal() throws Exception {
        byte[] original = cameraJpeg();
        String photo = claim(true, "dog-one", "image/jpeg", original);
        String thumbnail = thumbnailOf("dog-one");
        assertThat(thumbnail).isNotBlank().isNotEqualTo(photo);
        assertThat(grant(thumbnail)).containsEntry("purpose", PhotoThumbnails.PURPOSE).containsEntry("boundEntity", "dog-one").containsEntry("clubId", CLUB)
                .containsEntry("mimeType", "image/jpeg");
        assertThat(exists(thumbnail)).isTrue();

        // GET /dogs (D15, the instructor's search): the rendition beside the photo, for ADMIN and INSTRUCTOR alike.
        for (var reader : List.of("ADMIN", "INSTRUCTOR")) {
            var row = json(call(get("/api/v1/dogs").param("fields", "id,photoUrl,photoThumbnailUrl").param("filter", "id:eq:dog-one"), CLUB, "admin", reader), 200)
                    .path("items").get(0);
            assertThat(row.size()).isEqualTo(3);
            assertThat(fetch(row.path("photoUrl").asText())).isEqualTo(original);
            var small = picture(row.path("photoThumbnailUrl").asText());
            assertThat(small.getWidth()).isEqualTo(192); assertThat(small.getHeight()).isEqualTo(256);
            assertThat(TestPictures.side(small, "right")).isEqualTo("red"); assertThat(TestPictures.side(small, "left")).isEqualTo("white");
            var sparse = json(call(get("/api/v1/dogs").param("fields", "id,photoUrl").param("filter", "id:eq:dog-one"), CLUB, "admin", reader), 200).path("items").get(0);
            assertThat(sparse.has("photoThumbnailUrl")).isFalse();
            var without = json(call(get("/api/v1/dogs").param("fields", "id,photoUrl,photoThumbnailUrl").param("filter", "id:eq:dog-two"), CLUB, "admin", reader), 200).path("items").get(0);
            assertThat(without.size()).isEqualTo(1);
        }
        byte[] thumbnailBytes = fetch(json(admin(get("/api/v1/dogs").param("fields", "id,photoThumbnailUrl").param("filter", "id:eq:dog-one")), 200)
                .at("/items/0/photoThumbnailUrl").asText());
        assertThat(TestPictures.contains(thumbnailBytes, "FictionalCam")).isFalse(); assertThat(TestPictures.contains(thumbnailBytes, "Exif")).isFalse();
        assertThat(thumbnailBytes.length).isLessThan(original.length);

        // GET /members (D5) rows' dogs[], the instructor's member record and D10's overview.
        for (var reader : List.of("ADMIN", "INSTRUCTOR")) {
            var listed = dogIn(json(call(get("/api/v1/members").param("fields", "dogs").param("filter", "id:eq:" + CLUB + "-one"), CLUB, "admin", reader), 200)
                    .at("/items/0/dogs"), "dog-one");
            assertThat(picture(listed.path("photoThumbnailUrl").asText()).getHeight()).isEqualTo(256);
            assertThat(fetch(listed.path("photoUrl").asText())).isEqualTo(original);
            assertThat(dogIn(json(call(get("/api/v1/members").param("fields", "dogs").param("filter", "id:eq:" + CLUB + "-one"), CLUB, "admin", reader), 200)
                    .at("/items/0/dogs"), "dog-two").has("photoThumbnailUrl")).isFalse();
        }
        var record = dogIn(json(instructor(get("/api/v1/members/" + CLUB + "-one")), 200).path("dogs"), "dog-one");
        assertThat(picture(record.path("photoThumbnailUrl").asText()).getWidth()).isEqualTo(192);
        var overview = dogIn(json(admin(get("/api/v1/members/" + CLUB + "-one/overview")), 200).path("dogs"), "dog-one");
        assertThat(picture(overview.path("photoThumbnailUrl").asText()).getWidth()).isEqualTo(192);
        assertThat(fetch(overview.path("photoUrl").asText())).isEqualTo(original);
        assertThat(dogIn(json(admin(get("/api/v1/members/" + CLUB + "-one/overview")), 200).path("dogs"), "dog-two").has("photoThumbnailUrl")).isFalse();

        // Detail responses keep the original only; no response names the stored keys.
        var detail = json(admin(get("/api/v1/dogs/dog-one")), 200);
        assertThat(fetch(detail.at("/dog/photoUrl").asText())).isEqualTo(original);
        assertThat(detail.toString()).doesNotContain("photoThumbnail");
        assertThat(json(own(get("/api/v1/me/dogs")), 200).toString()).doesNotContain("photoThumbnail");
        assertThat(json(admin(get("/api/v1/dogs").param("filter", "id:eq:dog-one")), 200).toString()).doesNotContain("photoThumbnailFileKey", "photoFileKey");
        // Club scope and roles as for the photo: another club lists nothing of it, a MEMBER cannot list dogs.
        assertThat(json(call(get("/api/v1/dogs").param("fields", "id,photoThumbnailUrl"), OTHER, "foreign", "ADMIN"), 200).toString()).doesNotContain(thumbnail, "dog-one");
        own(get("/api/v1/dogs").param("fields", "id,photoThumbnailUrl")).andExpect(status().isForbidden());
        // The audit records the photo, never its rendition.
        var audits = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is(AuditAction.DOG_UPDATED).and("entityId").is("dog-one")), AuditEntry.class);
        assertThat(audits).isNotEmpty();
        assertThat(mapper.writeValueAsString(audits)).contains("photoFileKey").doesNotContain("photoThumbnailFileKey");
        // Short-lived like the photo's URL (five minutes; on S3 the presigned URL's own expiry, `X-Amz-Expires=300`).
        String url = json(admin(get("/api/v1/dogs").param("fields", "id,photoThumbnailUrl").param("filter", "id:eq:dog-one")), 200).at("/items/0/photoThumbnailUrl").asText();
        if (url.startsWith("http")) { assertThat(url).contains("X-Amz-Expires=300"); return; }
        clock.advance(Duration.ofMinutes(6));
        admin(get(url)).andExpect(status().isForbidden());
    }

    @Test void WEB_05_replacingThePhotoErasesItsThumbnailAndAPhotoTheServerCannotReduceHasNone() throws Exception {
        claim(false, "dog-one", "image/png", TestPictures.png(TestPictures.quadrants(800, 600)));
        String first = thumbnailOf("dog-one");
        assertThat(first).isNotBlank();
        String photo = claim(true, "dog-one", "image/jpeg", TestPictures.jpeg(TestPictures.quadrants(480, 640), false));
        String second = thumbnailOf("dog-one");
        assertThat(second).isNotBlank().isNotEqualTo(first);
        assertThat(exists(first)).isFalse(); assertThat(grant(first)).isNull();
        assertThat(exists(second)).isTrue();
        var row = json(admin(get("/api/v1/dogs").param("fields", "id,photoThumbnailUrl").param("filter", "id:eq:dog-one")), 200).at("/items/0");
        assertThat(picture(row.path("photoThumbnailUrl").asText()).getHeight()).isEqualTo(256);
        // The same photo claimed again changes nothing.
        long events = mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("DogUpdated")), "domain_events");
        own(body(put("/api/v1/me/dogs/dog-one/photo"), Map.of("fileKey", photo))).andExpect(status().isOk());
        assertThat(thumbnailOf("dog-one")).isEqualTo(second);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("DogUpdated")), "domain_events")).isEqualTo(events);

        // A WebP (or any picture the JDK cannot decode with certainty) is claimed as before, without a rendition; the old one goes.
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 fictional".getBytes(StandardCharsets.ISO_8859_1);
        claim(true, "dog-one", "image/webp", webp);
        assertThat(stored("dog-one")).doesNotContainKey("photoThumbnailFileKey");
        assertThat(exists(second)).isFalse(); assertThat(grant(second)).isNull();
        var listed = json(admin(get("/api/v1/dogs").param("fields", "id,photoUrl,photoThumbnailUrl").param("filter", "id:eq:dog-one")), 200).at("/items/0");
        assertThat(listed.has("photoThumbnailUrl")).isFalse(); assertThat(fetch(listed.path("photoUrl").asText())).isEqualTo(webp);
        assertThat(dogIn(json(admin(get("/api/v1/members/" + CLUB + "-one/overview")), 200).path("dogs"), "dog-one").has("photoThumbnailUrl")).isFalse();
        // A truncated JPEG: the same.
        byte[] jpeg = TestPictures.jpeg(TestPictures.quadrants(640, 480), false);
        claim(false, "dog-one", "image/jpeg", Arrays.copyOf(jpeg, jpeg.length / 2));
        assertThat(stored("dog-one")).doesNotContainKey("photoThumbnailFileKey");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("purpose").is(PhotoThumbnails.PURPOSE)), "attachment_uploads")).isZero();
    }

    @Test void WEB_05_aMigratedPhotoGetsItsThumbnailWhenItIsBound() throws Exception {
        try (var tenant = TenantContext.open(CLUB)) {
            var file = attachments.storeMigrationPhoto("image/png", TestPictures.png(TestPictures.quadrants(300, 200)));
            assertThat(documents.migrationPhoto("dog-two", file)).isTrue();
        }
        String thumbnail = thumbnailOf("dog-two");
        assertThat(thumbnail).isNotBlank();
        assertThat(grant(thumbnail)).containsEntry("boundEntity", "dog-two").containsEntry("purpose", PhotoThumbnails.PURPOSE);
        var row = json(admin(get("/api/v1/dogs").param("fields", "id,photoThumbnailUrl").param("filter", "id:eq:dog-two")), 200).at("/items/0");
        var small = picture(row.path("photoThumbnailUrl").asText());
        assertThat(small.getWidth()).isEqualTo(256); assertThat(small.getHeight()).isEqualTo(171);
    }

    @Test void WEB_05_R_14_15_theErasureDeletesTheThumbnailWithThePhoto() throws Exception {
        String photo = claim(true, "dog-one", "image/jpeg", TestPictures.jpeg(TestPictures.quadrants(640, 480), false));
        String thumbnail = thumbnailOf("dog-one");
        assertThat(thumbnail).isNotBlank();
        var files = new ArrayList<String>();
        try (var tenant = TenantContext.open(CLUB)) {
            var subject = new ErasureSubject("request-web-05", CLUB, List.of(new ErasureMember(CLUB + "-one", "thumbs-" + CLUB + "-one", 1, "LEFT",
                    LocalDate.of(2026, 8, 31), null, "LEAVE_REQUEST", null, List.of("dog-one", "dog-two"))), Set.of("thumbs-" + CLUB + "-one"),
                    Map.of("thumbs-" + CLUB + "-one", "erased+fictional@erased.invalid"), new ErasurePlaceholders("Abonat suprimit", "Compte suprimit", "Gos suprimit", "(suprimit)"),
                    clock.instant());
            files.addAll(followupErasure.erase(subject).files());
            files.addAll(censusErasure.erase(subject).files());
        }
        assertThat(files).contains("attachments:" + photo, "attachments:" + thumbnail);
        assertThat(stored("dog-one")).doesNotContainKeys("photoFileKey", "photoThumbnailFileKey");
        assertThat(grant(thumbnail)).isNull();
        // S14 §7: the cleanup deletes each pending file from its store.
        files.stream().filter(file -> file.startsWith("attachments:")).forEach(file -> followupErasure.delete(file.substring("attachments:".length())));
        assertThat(exists(thumbnail)).isFalse(); assertThat(exists(photo)).isFalse();
    }

    /** A photo stored before WEB-05: its object, its `DOG_PHOTO` grant bound to the dog, the dog's key; no rendition. */
    String legacy(String club, String dogId, String type, byte[] bytes) throws Exception {
        String key = UUID.randomUUID().toString();
        storage.put(key, type, bytes.length, new ByteArrayInputStream(bytes));
        mongo.insert(new UploadGrant(key, club, "thumbs-" + club + "-one", "DOG_PHOTO", "photo", type, bytes.length, clock.instant(), clock.instant(), dogId));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(dogId)), new Update().set("photoFileKey", key), "dogs");
        return key;
    }
    String run(String... arguments) {
        var out = new ByteArrayOutputStream(); var previous = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { command.run(new DefaultApplicationArguments(arguments)); } finally { System.setOut(previous); }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test void WEB_05_theBackfillMakesTheMissingThumbnailsOfOneClubOnceAndItsDryRunWritesNothing() throws Exception {
        legacy(CLUB, "dog-one", "image/jpeg", cameraJpeg());
        legacy(CLUB, "dog-two", "image/webp", "RIFF\0\0\0\0WEBPVP8 fictional".getBytes(StandardCharsets.ISO_8859_1));
        legacy(CLUB, "dog-three", "image/png", TestPictures.png(TestPictures.quadrants(400, 300)));
        legacy(OTHER, "dog-foreign", "image/png", TestPictures.png(TestPictures.quadrants(400, 300)));
        var before = mongo.findAll(Document.class, "attachment_uploads").size();

        String dry = run("--club=" + CLUB, "--dry-run");
        assertThat(dry).contains("dog-one WOULD_MAKE", "dog-two WOULD_MAKE", "dog-three WOULD_MAKE", "3 dog photos without a thumbnail (dry run: nothing written)")
                .doesNotContain("dog-foreign", "Example");
        assertThat(mongo.count(Query.query(Criteria.where("photoThumbnailFileKey").exists(true)), "dogs")).isZero();
        assertThat(mongo.findAll(Document.class, "attachment_uploads")).hasSize(before);

        String applied = run("--club=" + CLUB);
        assertThat(applied).contains("dog-one MADE", "dog-two NOT_MADE", "dog-three MADE", "2 thumbnails made, 1 photos without one").doesNotContain("dog-foreign");
        assertThat(thumbnailOf("dog-one")).isNotBlank(); assertThat(thumbnailOf("dog-three")).isNotBlank();
        assertThat(stored("dog-two")).doesNotContainKey("photoThumbnailFileKey"); assertThat(stored("dog-foreign")).doesNotContainKey("photoThumbnailFileKey");
        var upright = picture(json(admin(get("/api/v1/dogs").param("fields", "id,photoThumbnailUrl").param("filter", "id:eq:dog-one")), 200).at("/items/0/photoThumbnailUrl").asText());
        assertThat(upright.getWidth()).isEqualTo(192); assertThat(TestPictures.side(upright, "right")).isEqualTo("red");

        // Idempotent and safe to re-run: the dogs with a rendition are not touched again.
        var made = List.of(thumbnailOf("dog-one"), thumbnailOf("dog-three"));
        String again = run("--club=" + CLUB);
        assertThat(again).contains("dog-two NOT_MADE", "0 thumbnails made, 1 photos without one").doesNotContain("dog-one", "dog-three");
        assertThat(List.of(thumbnailOf("dog-one"), thumbnailOf("dog-three"))).isEqualTo(made);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("purpose").is(PhotoThumbnails.PURPOSE)), "attachment_uploads")).isEqualTo(2);
        // An erased dog is never listed.
        mongo.updateFirst(Query.query(Criteria.where("_id").is("dog-two")), new Update().set("erasedAt", clock.instant()), "dogs");
        assertThat(run("--club=" + CLUB, "--dry-run")).contains("0 dog photos without a thumbnail");
        assertThatThrownBy(() -> run("--dry-run")).hasMessage("Usage: census:photo-thumbnails --club=slug [--dry-run]");
        assertThatThrownBy(() -> run("--club=" + CLUB, "--apply")).hasMessage("Usage: census:photo-thumbnails --club=slug [--dry-run]");
        assertThatThrownBy(() -> run("--club=unknown-club")).hasMessage("NOT_FOUND");
    }
}
