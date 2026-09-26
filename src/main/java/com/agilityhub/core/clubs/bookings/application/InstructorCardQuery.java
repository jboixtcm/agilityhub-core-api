package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.DogFollowupPort;
import com.agilityhub.core.clubs.bookings.application.ports.TrainingStatsQuery;
import com.agilityhub.core.clubs.bookings.domain.AttendanceMetrics;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.AttendanceCensusAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.bookings.application.BookingViews.map;

/**
 * S10 R-10-08/R-10-09 `GET /dogs/{id}/instructor-card` (22, D13), any dog of the club (global vision). The 30-day
 * metrics over the window `[now − 30 days, now)` in the club's time zone ({@link AttendanceMetrics}; assumption §13-1:
 * `cancelledLate` is in the denominator), the trainings of that window per dog (§13-8, only with FREE_TRAINING), the
 * last 5 classes with their badge and the level with its `assignedAt` (the front's «fa {n} mesos»; absent with
 * `levels.enabled = false`). The note, tasks and observations blocks come from {@link DogFollowupPort}, only with TASKS
 * and once E6-T03 serves the port (absent otherwise, the TASKS-off shape). `Dog.remarks` is never read here.
 */
@Service
public class InstructorCardQuery {
    private final BookingContext context; private final AttendanceCensusAccess census; private final BookingRepository bookings;
    private final AttendanceRepository attendances; private final ClassSessionBookingAccess classes; private final PlanningCatalogAccess catalogs;
    private final TrainingStatsQuery trainings; private final DogFollowupPort followup;
    public InstructorCardQuery(BookingContext context, AttendanceCensusAccess census, BookingRepository bookings, AttendanceRepository attendances,
            ClassSessionBookingAccess classes, PlanningCatalogAccess catalogs, TrainingStatsQuery trainings, DogFollowupPort followup) {
        this.context = context; this.census = census; this.bookings = bookings; this.attendances = attendances; this.classes = classes;
        this.catalogs = catalogs; this.trainings = trainings; this.followup = followup;
    }

    public Map<String, Object> card(String dogId) {
        var pair = census.pair(dogId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var now = context.now(); var zone = context.zone(); var locale = LocaleContext.current();
        var from = now.atZone(zone).minusDays(AttendanceMetrics.WINDOW_DAYS).toInstant();
        var past = bookings.forDogs(List.of(dogId), List.of(), null, now.plusMillis(1));
        var marks = attendances.byBookings(past.stream().map(Booking::id).toList());
        var records = past.stream().map(b -> {
            var a = marks.get(b.id());
            return new AttendanceMetrics.ClassRecord(b.id(), b.classStartsAt(), b.state(), a == null ? AttendanceState.PENDING : a.state(),
                    a != null && a.notice() != null && a.notice().afterClassEnd());
        }).toList();
        var metrics = AttendanceMetrics.of(records, from, now);
        var metricsView = map("windowDays", metrics.windowDays(), "attendancePct", metrics.attendancePct(), "present", metrics.present(), "noShow", metrics.noShow(),
                "notified", metrics.notified(), "cancelledLate", metrics.cancelledLate(), "classesCounted", metrics.classesCounted());
        if (context.enabled(Module.FREE_TRAINING)) {
            int count = trainings.countDone(dogId, from, now);
            metricsView.put("trainingsCount", count); metricsView.put("trainingsPerWeek", AttendanceMetrics.perWeek(count));
        }
        var last = AttendanceMetrics.lastClasses(records, now);
        var byId = new HashMap<String, Booking>(); past.forEach(b -> byId.put(b.id(), b));
        var labels = classes.labels(last.stream().map(l -> byId.get(l.record().bookingId()).classSessionId()).distinct().toList(), locale);
        var lastClasses = last.stream().map(l -> {
            var b = byId.get(l.record().bookingId()); var label = labels.get(b.classSessionId());
            return map("bookingId", b.id(), "date", b.classStartsAt().atZone(zone).toLocalDate(), "displayDescription", label == null ? "" : label.description(),
                    "ringName", label == null ? null : label.ringName(), "instructorName", label == null || label.instructorNames().isEmpty() ? null : label.instructorNames(),
                    "displayState", l.display());
        }).toList();
        var status = census.memberStatus(pair.memberId());
        var out = map("dog", map("id", pair.dogId(), "name", pair.dogName(), "breed", pair.breed(), "sex", pair.sex(), "ageYears", census.ageYears(pair),
                        "photoUrl", census.photoUrl(pair), "handlerName", pair.handlerName(), "status", pair.dogStatus()),
                "member", map("id", pair.memberId(), "firstName", pair.memberFirstName(), "fullName", pair.memberFullName(), "gender", pair.gender(),
                        "displayStatus", map("kind", status.kind(), "date", status.date())));
        var level = context.flag("levels.enabled") && pair.levelId() != null ? catalogs.levelRefs().get(pair.levelId()) : null;
        if (level != null) { out.put("level", map("code", level.code(), "name", level.name().resolve(locale).value(), "assignedAt", pair.levelAssignedAt())); }
        out.put("metrics", metricsView);
        out.put("lastClasses", lastClasses);
        if (context.enabled(Module.TASKS) && followup.available()) {
            followup.instructorNote(dogId).ifPresent(n -> out.put("instructorNote", map("text", n.text(), "updatedAt", n.updatedAt(), "attachments", attachments(n.attachments()))));
            var tasks = followup.tasks(dogId);
            out.put("tasks", map("pendingCount", tasks.pendingCount(), "doneCount", tasks.doneCount(), "latest", tasks.latest() == null ? null
                    : map("id", tasks.latest().id(), "text", tasks.latest().text(), "createdAt", tasks.latest().createdAt(), "createdByName", tasks.latest().createdByName())));
            followup.observations(dogId).ifPresent(o -> out.put("observations", map("text", o.text(), "updatedAt", o.updatedAt(), "updatedByName", o.updatedByName(),
                    "attachments", attachments(o.attachments()), "version", o.version())));
        }
        return out;
    }
    private static List<Map<String, Object>> attachments(List<DogFollowupPort.NoteAttachment> list) {
        return list == null ? List.of() : list.stream().map(a -> map("id", a.id(), "name", a.name(), "mimeType", a.mimeType(), "url", a.url())).toList();
    }
}
