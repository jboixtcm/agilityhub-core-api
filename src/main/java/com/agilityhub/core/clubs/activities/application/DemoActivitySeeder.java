package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.ActivityMemberAccess;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.clubs.scheduling.application.*;
import com.agilityhub.core.shared.application.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** E4-T05: the D7 activities through the S07 services — drafts, publication with real ring blocking, member registrations and files. */
@Service
public class DemoActivitySeeder implements DemoSeedStep {
    public record Spec(Map<String, String> title, ActivityType type, int week, DayOfWeek day, String startTime, String endTime, List<String> rings,
            List<String> levels, Map<String, Object> location, Integer maxPlaces, boolean waitlistEnabled, int registrationClosesDaysBefore,
            boolean publish, Publication publication, int registrants, int waitlisted, boolean files) {
        public Spec {
            rings = rings == null ? List.of() : List.copyOf(rings); levels = levels == null ? List.of() : List.copyOf(levels);
            if (registrants < 0 || waitlisted < 0 || (!publish && registrants + waitlisted > 0)) { throw new IllegalArgumentException("Invalid demo activity"); }
        }
    }
    public record Publication(boolean cancelClasses, String adminText) { }
    private record Candidate(String memberId, String accountId, Set<String> levels) { }
    /** 1×1 transparent PNG: the smallest valid fictional image. */
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    private final ActivityService activities; private final ActivityLifecycleService lifecycle; private final ActivityRegistrationService registrations;
    private final AttachmentService attachments; private final ActivityMemberAccess members; private final PlanningCatalogAccess catalogs;
    private final ClubClock clubClock; private final ObjectMapper mapper;
    public DemoActivitySeeder(ActivityService activities, ActivityLifecycleService lifecycle, ActivityRegistrationService registrations,
            AttachmentService attachments, ActivityMemberAccess members, PlanningCatalogAccess catalogs, ClubClock clubClock, ObjectMapper mapper) {
        this.activities = activities; this.lifecycle = lifecycle; this.registrations = registrations; this.attachments = attachments;
        this.members = members; this.catalogs = catalogs; this.clubClock = clubClock; this.mapper = mapper;
    }
    @Override public int order() { return 30; }
    @Override public Map<String, Integer> apply(Input input) {
        List<Spec> specs = mapper.convertValue(input.specification().getOrDefault("activities", List.of()), new TypeReference<>() { });
        var counts = new LinkedHashMap<String, Integer>();
        for (String key : List.of("activities", "publishedActivities", "activityRegistrations", "activityWaitlist", "activityFiles")) { counts.put(key, 0); }
        if (specs.isEmpty()) { return counts; }
        var rings = catalogs.ringIdsByShortName(); var levels = catalogs.levelIdsByCode(); var pool = candidates(input.loginMemberIds());
        var today = clubClock.today(TenantContext.require());
        for (int index = 0; index < specs.size(); index++) {
            var spec = specs.get(index); var date = DemoPlanningSeeder.date(input.weekStart(), spec.week(), spec.day());
            var created = activities.create(spec.title(), spec.type());
            var patch = new LinkedHashMap<String, Object>();
            if (spec.location() != null) { patch.put("location", spec.location()); }
            patch.put("ringIds", spec.rings().stream().map(r -> DemoPlanningSeeder.require(rings, r)).toList());
            patch.put("levelIds", spec.levels().stream().map(l -> DemoPlanningSeeder.require(levels, l)).toList());
            patch.put("date", date); patch.put("startTime", spec.startTime()); patch.put("endTime", spec.endTime());
            patch.put("registrationFrom", today); patch.put("registrationTo", date.minusDays(spec.registrationClosesDaysBefore()));
            patch.put("maxPlaces", spec.maxPlaces()); patch.put("waitlistEnabled", spec.waitlistEnabled());
            var draft = activities.patch(created.id(), created.version(), patch, new RingBlockService.Options(false, false, null));
            counts.merge("activities", 1, Integer::sum);
            if (spec.files()) {
                upload(draft.id(), "ACTIVITY_IMAGE", "demo-image.png", "image/png", PNG); upload(draft.id(), "ACTIVITY_DOCUMENT", "demo-document.pdf", "application/pdf", pdf());
                counts.merge("activityFiles", 2, Integer::sum);
            }
            if (!spec.publish()) { continue; }
            var publication = spec.publication() == null ? new Publication(false, null) : spec.publication();
            lifecycle.publish(draft.id(), false, new RingBlockService.Options(false, publication.cancelClasses(), publication.adminText()));
            counts.merge("publishedActivities", 1, Integer::sum);
            var levelIds = new HashSet<>((List<?>) patch.get("levelIds"));
            var eligible = new ArrayList<>(pool.stream().filter(c -> levelIds.isEmpty() || !Collections.disjoint(c.levels(), levelIds)).toList());
            Collections.shuffle(eligible, new Random(input.seed() * 131 + index));
            if (eligible.size() < spec.registrants() + spec.waitlisted()) { throw new IllegalStateException("Not enough eligible demo members for " + spec.title()); }
            for (int i = 0; i < spec.registrants() + spec.waitlisted(); i++) {
                var member = eligible.get(i); boolean waitlist = i >= spec.registrants();
                DemoSeedActor.as(member.accountId(), "MEMBER", () -> registrations.register(draft.id(), waitlist));
                counts.merge(waitlist ? "activityWaitlist" : "activityRegistrations", 1, Integer::sum);
            }
        }
        return counts;
    }
    private void upload(String activity, String purpose, String name, String type, byte[] body) {
        var grant = attachments.upload(purpose, name, type, body.length); var query = new HashMap<String, String>();
        for (String field : URI.create(grant.uploadUrl()).getRawQuery().split("&")) { var pair = field.split("=", 2); query.put(pair[0], pair[1]); }
        try { attachments.putLocal(grant.fileKey(), Long.parseLong(query.get("expires")), query.get("signature"), type, new ByteArrayInputStream(body)); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        if (purpose.equals("ACTIVITY_IMAGE")) { activities.image(activity, grant.fileKey(), name); } else { activities.document(activity, grant.fileKey(), name); }
    }
    private static byte[] pdf() {
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(); pdf.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 750); content.showText("Fictional demo document."); content.endText();
            }
            pdf.save(bytes); return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    /** ACTIVE members with an app account (except the seed logins) and the levels of their ACTIVE dogs, in member-number order. */
    private List<Candidate> candidates(Set<String> excluded) {
        var result = new ArrayList<Candidate>();
        for (var member : members.activeIds().stream().filter(id -> !excluded.contains(id)).map(members::member)
                .sorted(Comparator.comparing((ActivityMemberAccess.Member m) -> m.number().length()).thenComparing(ActivityMemberAccess.Member::number)).toList()) {
            String id = member.id();
            var dogLevels = new HashSet<String>(); member.dogs().stream().filter(d -> "ACTIVE".equals(d.status())).forEach(d -> dogLevels.add(d.levelId()));
            if (member.accountId() != null && member.membershipActive() && !dogLevels.isEmpty()) { result.add(new Candidate(id, member.accountId(), dogLevels)); }
        }
        return result;
    }
}
