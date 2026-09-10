package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.DemoDataset;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.identity.application.DemoIdentityService;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local demo bootstrap. A completed run is insert-only and never resets subsequent demo edits. */
@Service
public class DemoSeedService {
    private final CensusAccess census; private final CensusRepository<DogDocument> documents; private final DemoSeedRepository runs;
    private final MigrationCatalogAccess catalogs; private final RoleAssignmentService team; private final DemoIdentityService identity;
    private final com.agilityhub.core.platform.application.MigrationClubAccess clubs;
    private final ClubConfigService configs; private final ObjectMapper mapper; private final Environment environment;
    private final AttachmentService attachments; private final DocumentService documentService;
    private final DemoSignupSeeder signupSeeder;
    public DemoSeedService(CensusAccess census, CensusRepository<DogDocument> documents, DemoSeedRepository runs,
            MigrationCatalogAccess catalogs, RoleAssignmentService team, DemoIdentityService identity, ClubConfigService configs,
            ObjectMapper mapper, Environment environment, AttachmentService attachments, DocumentService documentService,
            com.agilityhub.core.platform.application.MigrationClubAccess clubs, DemoSignupSeeder signupSeeder) {
        this.census = census; this.documents = documents; this.runs = runs; this.catalogs = catalogs; this.team = team;
        this.identity = identity; this.configs = configs; this.mapper = mapper; this.environment = environment;
        this.attachments = attachments; this.documentService = documentService; this.clubs = clubs;
        this.signupSeeder = signupSeeder;
    }
    public record Result(String id, int changes, Map<String, Integer> counts,
            @com.agilityhub.core.shared.domain.audit.AuditField Map<String, Integer> summary) {
        public Result(String id, int changes, Map<String, Integer> counts) { this(id, changes, counts, changes == 0 ? Map.of() : counts); }
        public String render() { return counts + "\n" + changes + " changes (demo seed)"; }
    }
    @Transactional
    @Audited(action = AuditAction.CATALOG_CHANGED, entityType = "'DemoSeed'", entity = "#result.id",
            reason = "#result.changes == 0 ? null : 'source: DEMO_SEED'")
    public Result apply(DemoDataset.Spec spec, long seed) {
        if (!environment.acceptsProfiles(Profiles.of("local", "test")) || environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        String club = TenantContext.require(); String signature = mapper.valueToTree(spec).toString();
        var previous = runs.findById(club).orElse(null);
        if (previous != null) {
            if (previous.seed() != seed || !previous.specification().equals(signature)) { throw new ApiException(ErrorCode.CLUB_NOT_EMPTY); }
            return new Result(club, 0, previous.counts());
        }
        census.members.lock(); catalogs.lock();
        if (!census.members.findAll().isEmpty() || !census.dogs.findAll().isEmpty() || !census.groups.findAll().isEmpty()) {
            throw new ApiException(ErrorCode.CLUB_NOT_EMPTY);
        }
        var config = configs.get(club); var levels = catalogByCode("levels"); var plans = catalogByCode("plans");
        var prices = catalogs.rows("prices");
        var data = DemoDataset.generate(spec, seed, club);
        var created = new LinkedHashMap<String, Member>();
        Instant reference = spec.referenceDate().atStartOfDay(ZoneId.of(config.club().timeZone())).toInstant();
        for (var row : data.members()) {
            var plan = require(plans, row.planCode());
            var member = new Member(); member.id = row.id(); member.clubId = club; member.memberNumber = row.number();
            member.firstName = row.firstName(); member.lastName1 = row.surname(); member.lastName2 = ""; member.status = row.status();
            member.birthDate = spec.referenceDate().minusYears(20 + row.number() % 45);
            member.sourceIds = Map.of("demo", row.number()); member.contactEmails = List.of(Map.of("email", row.email(), "label", "Personal"));
            member.phones = List.of(); member.joinedAt = reference.minusSeconds(86400L * (30 + row.number()));
            member.planId = plan.get("_id").toString();
            member.priceId = prices.stream().filter(p -> member.planId.equals(p.get("planId")) && p.get("validTo") == null)
                    .map(p -> p.get("_id").toString()).findFirst().orElse(null);
            member.paymentMethod = row.number() % 3 == 0 ? Map.of("type", "MANUAL", "channel", "cash")
                    : Map.of("type", "SEPA_DD", "iban", row.iban(), "holderName", row.firstName() + " " + row.surname());
            member.consents = Map.of("imageRights", Map.of("accepted", row.number() % 2 == 0, "at", reference));
            member.bookingBlock = Map.of("active", false); member.notificationPreferences = Map.of();
            if ("LEFT".equals(row.status())) { member.leftAt = reference.minusSeconds(86400L * 10); member.leftReason = "Fictional demo leave"; }
            if (!"PENDING".equals(member.status)) {
                member.accountId = identity.link(member.id, row.email(), member.firstName + " " + member.lastName1, config.club().defaultLocale(),
                        Set.of("ACTIVE", "INACTIVE").contains(member.status));
            }
            census.members.insert(member); created.put(member.id, member);
        }
        for (int i = 0; i < spec.familyGroups(); i++) {
            var holder = created.get(data.members().get(i * 2).id()); var other = created.get(data.members().get(i * 2 + 1).id());
            var group = new FamilyGroup(); group.id = DemoDataset.id(club, "family", i); group.clubId = club;
            group.holderMemberId = holder.id; group.memberIds = List.of(holder.id, other.id); group.status = "ACTIVE";
            census.groups.insert(group); holder.familyGroupId = group.id; other.familyGroupId = group.id;
            census.members.save(holder); census.members.save(other);
        }
        var requiredDocuments = ((List<?>) config.get("census.dogDocumentTypes", List.class)).stream().map(item -> (Map<?, ?>) item)
                .filter(item -> Boolean.TRUE.equals(item.get("required"))).map(item -> item.get("key").toString()).toList();
        for (var row : data.dogs()) {
            var level = require(levels, row.levelCode());
            var dog = new Dog(); dog.id = row.id(); dog.clubId = club; dog.memberId = row.memberId(); dog.name = row.name();
            dog.sourceIds = Map.of("demo", dog.id); dog.breed = "Fictional mixed breed"; dog.sex = "FEMALE"; dog.birthDate = row.birthDate();
            dog.status = "ACTIVE"; dog.registeredAt = reference; dog.levelId = level.get("_id").toString(); dog.levelAssignedAt = reference;
            dog.levelHistory = List.of(); dog.licenses = List.of(); dog.handlerName = created.get(dog.memberId).firstName;
            census.dogs.insert(dog);
            for (String type : requiredDocuments) {
                var document = new DogDocument(); document.id = documentService.documentId(dog.id, type); document.clubId = club;
                document.dogId = dog.id; document.type = type; document.state = "PENDING"; document.files = List.of();
                documents.insert(document);
            }
        }
        for (int index : spec.instructors()) {
            var member = data.members().get(index); team.createInstructor(member.id(), member.firstName(),
                    config.ringPalette().isEmpty() ? config.primaryColor() : config.ringPalette().get(index % config.ringPalette().size()));
        }
        for (int index : spec.administrators()) { var member = data.members().get(index); team.createAdministrator(member.id(), member.firstName(), spec.referenceDate()); }
        if (!requiredDocuments.isEmpty()) {
            for (int i = 0; i < spec.receivedDocuments(); i++) {
                var dog = data.dogs().get(i); var owner = created.get(dog.memberId());
                receive(dog.id(), requiredDocuments.getFirst(), owner.accountId);
            }
        }
        var pendingMembers = created.values().stream().filter(m -> "PENDING".equals(m.status)).toList();
        for (int i = 0; i < pendingMembers.size(); i++) {
            signupSeeder.apply(pendingMembers.get(i), spec.pendingSignups().get(i), i, requiredDocuments);
        }
        int dogCount = data.dogs().size() + pendingMembers.size();
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("activeMembers", spec.activeMembers()); counts.put("pendingMembers", spec.pendingMembers());
        counts.put("inactiveMembers", spec.inactiveMembers()); counts.put("leftMembers", spec.leftMembers());
        counts.put("members", data.members().size()); counts.put("dogs", dogCount); counts.put("familyGroups", spec.familyGroups());
        counts.put("activeDogs", data.dogs().size()); counts.put("pendingDogs", pendingMembers.size());
        counts.put("instructors", spec.instructors().size()); counts.put("administrators", spec.administrators().size());
        counts.put("receivedDocuments", requiredDocuments.isEmpty() ? 0 : spec.receivedDocuments());
        counts.put("pendingDocuments", dogCount * requiredDocuments.size() - counts.get("receivedDocuments"));
        clubs.reserveNumbers(data.members().size());
        runs.insert(new DemoSeedRun(club, club, seed, signature, counts));
        return new Result(club, data.members().size() + dogCount + spec.familyGroups(), counts);
    }
    private Map<String, Map<String, Object>> catalogByCode(String collection) {
        var result = new HashMap<String, Map<String, Object>>();
        catalogs.rows(collection).forEach(row -> result.put(row.get("code").toString(), row)); return result;
    }
    private Map<String, Object> require(Map<String, Map<String, Object>> catalog, String code) {
        var value = catalog.get(code); if (value == null) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("catalogCode", code)); } return value;
    }
    private void receive(String dog, String type, String account) {
        try (var user = CurrentUser.open(new CurrentUser(account, "Demo seed", null, DomainEvent.Origin.SYSTEM));
                var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(); pdf.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 750); content.showText("Fictional demo document. Not a veterinary record."); content.endText();
            }
            pdf.save(bytes); byte[] body = bytes.toByteArray();
            var grant = attachments.upload("DOG_DOCUMENT", "demo-document.pdf", "application/pdf", body.length);
            var query = new HashMap<String, String>();
            for (String field : URI.create(grant.uploadUrl()).getRawQuery().split("&")) { var pair = field.split("=", 2); query.put(pair[0], pair[1]); }
            attachments.putLocal(grant.fileKey(), Long.parseLong(query.get("expires")), query.get("signature"), "application/pdf", new ByteArrayInputStream(body));
            documentService.upload(dog, type, "demo-document.pdf", grant.fileKey(), false);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
