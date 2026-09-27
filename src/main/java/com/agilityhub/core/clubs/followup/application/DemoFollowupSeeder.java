package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * E6-T04 demo follow-up of S10 WP-10-G (`scenario.attendance`, applied with {@link DemoSeedStep.Input#scenario()}; the
 * cast is named by the census step before it): on the holder's dog, the tasks of `tasks` created by the class instructor
 * through {@link TaskService} (one with a fictional attachment uploaded through the local signed URL, as the app does;
 * `completedBy` then completes it as that member, from screen 13), and the instructors' private observation
 * ({@link ObservationService}). Each write is the real one: `TaskCreated` (N-20), `TaskCompleted` (N-21), the D14 rows.
 */
@Service
public class DemoFollowupSeeder implements DemoSeedStep {
    public record Staff(int index, String name) { }
    public record Cast(String name, int member, String dog) { }
    public record Attachment(String name, String mimeType, int sizeBytes) { }
    public record TaskSpec(String text, Attachment attachment, String completedBy) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(Staff instructor, List<Cast> cast, List<TaskSpec> tasks, String observation) {
        public Spec { cast = cast == null ? List.of() : List.copyOf(cast); tasks = tasks == null ? List.of() : List.copyOf(tasks); }
    }
    private final TaskService tasks; private final FollowupActors actors; private final FollowupContractAccess access; private final AttachmentService attachments;
    private final ObservationService observations; private final FollowupCensusAccess census; private final PlanningCatalogAccess catalogs; private final ObjectMapper mapper;
    public DemoFollowupSeeder(TaskService tasks, FollowupActors actors, FollowupContractAccess access, AttachmentService attachments, ObservationService observations,
            FollowupCensusAccess census, PlanningCatalogAccess catalogs, ObjectMapper mapper) {
        this.tasks = tasks; this.actors = actors; this.access = access; this.attachments = attachments; this.observations = observations; this.census = census;
        this.catalogs = catalogs; this.mapper = mapper;
    }
    @Override public int order() { return 38; }
    @Override public Map<String, Integer> apply(Input input) {
        var counts = new LinkedHashMap<String, Integer>(); counts.put("followupTasks", 0); counts.put("followupTaskAttachments", 0); counts.put("followupObservations", 0);
        var section = input.scenario().get("attendance");
        if (section == null) { return counts; }
        var spec = mapper.convertValue(section, Spec.class);
        if (spec.cast().isEmpty() || spec.instructor() == null) { return counts; }
        var holder = spec.cast().getFirst(); String holderMember = input.member(holder.member());
        String dogId = census.dogsOf(holderMember).values().stream().filter(d -> "ACTIVE".equals(d.status()) && holder.dog().equals(d.name()))
                .map(FollowupCensusAccess.Dog::id).sorted().findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoCast", holder.name())));
        var instructorIds = catalogs.activeInstructorIds();
        if (spec.instructor().index() < 0 || spec.instructor().index() >= instructorIds.size()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("instructor", spec.instructor().index())); }
        String instructorId = instructorIds.get(spec.instructor().index()); String instructorMember = catalogs.instructorMembers(List.of(instructorId)).getFirst();
        String instructorAccount = account(instructorMember);
        var completions = new ArrayList<Map.Entry<Task, String>>();
        DemoSeedActor.as(instructorAccount, "INSTRUCTOR", () -> {
            var actor = actors.actor(access.caller(instructorMember), instructorMember, instructorId);
            for (var t : spec.tasks()) {
                var keys = t.attachment() == null ? List.<String>of() : List.of(upload(t.attachment()));
                var task = tasks.create(dogId, t.text(), keys, actor);
                if (t.completedBy() != null) { completions.add(Map.entry(task, t.completedBy())); }
                counts.merge("followupTasks", 1, Integer::sum); counts.merge("followupTaskAttachments", keys.size(), Integer::sum);
            }
            if (spec.observation() != null) {
                long version = census.remarks(dogId).map(FollowupCensusAccess.Remarks::version).orElse(0L);
                observations.save(dogId, spec.observation(), version, actor); counts.merge("followupObservations", 1, Integer::sum);
            }
            return null;
        });
        for (var completion : completions) {
            var member = spec.cast().stream().filter(c -> c.name().equals(completion.getValue())).findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoCast", completion.getValue())));
            String memberId = input.member(member.member());
            DemoSeedActor.as(account(memberId), "MEMBER", () -> tasks.complete(completion.getKey(), actors.actor(access.caller(memberId), memberId, null)));
        }
        return counts;
    }
    private String account(String memberId) {
        var member = census.members(List.of(memberId)).get(memberId);
        if (member == null || member.accountId() == null) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("demoMember", memberId)); }
        return member.accountId();
    }
    /** A TASK upload through the local signed URL (E5-T24), as the app does: a fictional MP4 header padded to `sizeBytes`. */
    private String upload(Attachment attachment) {
        byte[] body = fictionalMp4(attachment.sizeBytes());
        var grant = attachments.upload("TASK", attachment.name(), attachment.mimeType(), body.length);
        var query = new HashMap<String, String>();
        for (String field : URI.create(grant.uploadUrl()).getRawQuery().split("&")) { var pair = field.split("=", 2); query.put(pair[0], pair[1]); }
        try { attachments.putLocal(grant.fileKey(), Long.parseLong(query.get("expires")), query.get("signature"), attachment.mimeType(), new ByteArrayInputStream(body)); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        return grant.fileKey();
    }
    /** An ISO base media `ftyp` box (no real video), zero-padded: the file type check reads the declared MIME type only. */
    static byte[] fictionalMp4(int size) {
        byte[] header = "\0\0\0\u0018ftypisom\0\0\u0002\0isomiso2".getBytes(StandardCharsets.ISO_8859_1);
        if (size < header.length) { throw new IllegalArgumentException("Demo attachment too small"); }
        return Arrays.copyOf(header, size);
    }
}
