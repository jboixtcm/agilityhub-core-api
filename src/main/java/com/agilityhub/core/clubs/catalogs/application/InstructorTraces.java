package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.persistence.CatalogErasureRepository;
import com.agilityhub.core.clubs.catalogs.persistence.Instructor;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.privacy.ErasureDirectory;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.PrivacyTraces;
import com.agilityhub.core.platform.application.privacy.RetentionTask;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * S14 R-14-15 / S15 R-15-19 (E11-T16 round 2): an `InstructorChanged` of an erased member's instructor profile that was still
 * `PENDING` at the erasure is keyed by the instructor and carries the old `shortName` in its diff, with no `memberId`, so
 * {@link PrivacyTraces}' sweep does not see it. Once processed (`PUBLISHED` or `FAILED`), the monthly `retention-sweep` deletes
 * it here: one item per instructor profile of an erased member of the club that still has one.
 */
@Component
public class InstructorTraces implements RetentionTask {
    static final String TYPE = "InstructorChanged";
    private final ErasureDirectory directory;
    private final CatalogErasureRepository erasure;
    private final PrivacyTraces traces;
    public InstructorTraces(ErasureDirectory directory, CatalogErasureRepository erasure, PrivacyTraces traces) {
        this.directory = directory; this.erasure = erasure; this.traces = traces;
    }

    @Override public String entityType() { return "Instructor"; }
    @Override public String action() { return "PURGE_ERASED_TRACES"; }
    @Override public String counter() { return "erasedInstructorTracesPurged"; }
    /** The instructor profiles of the club's erased members that still have a processed `InstructorChanged`. */
    @Override public List<String> due(JobContext context) {
        var erased = directory.erased();
        if (erased.isEmpty()) { return List.of(); }
        var instructors = erasure.instructorsOf(erased.stream().map(ErasureMember::id).toList()).stream().map(Instructor::id).toList();
        return List.copyOf(traces.withProcessedEvents(TYPE, instructors));
    }
    /** Only the profile of an erased member: an active instructor's records are its own consumers' business. */
    @Override public boolean apply(JobContext context, String instructorId) {
        var instructor = erasure.instructor(instructorId).orElse(null);
        if (instructor == null || instructor.memberId() == null || directory.member(instructor.memberId()).filter(ErasureMember::erased).isEmpty()) { return false; }
        return traces.forgetProcessedEvents(TYPE, instructorId) > 0;
    }
}
