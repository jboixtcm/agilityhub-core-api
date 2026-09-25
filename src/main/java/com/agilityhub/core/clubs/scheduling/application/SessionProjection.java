package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.ports.ActivityTitlePort;
import com.agilityhub.core.clubs.scheduling.application.ports.AttendanceStatusPort;
import com.agilityhub.core.identity.application.CensusIdentityService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SessionProjection {
    private final PlanningContext context; private final PlanningCatalogAccess catalogs; private final IcuMessageSource messages;
    private final ActivityTitlePort activities; private final CensusIdentityService identities; private final Clock clock;
    private final AttendanceStatusPort attendance;
    public SessionProjection(PlanningContext context,PlanningCatalogAccess catalogs,IcuMessageSource messages,ActivityTitlePort activities,CensusIdentityService identities,Clock clock,
            AttendanceStatusPort attendance) {
        this.context=context; this.catalogs=catalogs; this.messages=messages; this.activities=activities; this.identities=identities; this.clock=clock; this.attendance=attendance;
    }
    /** S10 §7: the S10-owned summary read through the port, which counts each sheet row once (R-10-02). */
    public AttendanceStatusPort.AttendanceStatus attendanceStatus(ClassSession c) {
        var summary=c.attendanceSummary();
        return attendance.status(c.date(),c.state().name(),summary.marked(),c.counters().booked(),summary.notified(),summary.notifiedAfterEnd());
    }
    public boolean enabled(Module module) { return context.config().modules().contains(module); }
    /** The short column label of a class without a ring in `GET /day-grid` (ca «Sense», as on D3b). */
    public String noRing() { return messages.format("scheduling.noRing",Map.of(),LocaleContext.current()); }
    public ZoneId zone() { return ZoneId.of(context.config().club().timeZone()); }
    public RiskEvaluator.Result risk(ClassSession c) {
        var config=context.config();
        return new RiskEvaluator(messages::format).evaluate(new RiskEvaluator.Input(c.state(),c.risk().exempt(),c.counters().booked(),c.date()),
                new RiskEvaluator.Policy(config.get("classes.minDogs",Integer.class),LocalTime.parse(config.get("classes.riskReviewTime",String.class)),
                        config.get("classes.riskLookaheadDays",Integer.class),config.get("classes.riskAutoCancelSameDay",Boolean.class),zone()),clock.instant(),LocaleContext.current());
    }
    public String description(ClassSession c,Locale locale) { return context.descriptions().resolve(c.description(),c.levelIds(),context.catalog(),locale); }
    private static final List<String> PRODUCT_LANGUAGES=List.of("ca","es","en");
    /** S14 D1: the class description in the three product languages, falling back to the club's default language (E5-T10). */
    public LocalizedText descriptions(ClassSession c) {
        var values=new LinkedHashMap<String,String>(); for(String tag:PRODUCT_LANGUAGES) values.put(tag,description(c,Locale.forLanguageTag(tag)));
        return new LocalizedText(values,context.config().club().defaultLocale());
    }
    /**
     * S14 D1: the ring's name (never translated), or `dashboard.noRing` in each product language when the class has none
     * (E5-T10). E5-T15: a D1 row reads «Sense pista», not the day grid's short column label «Sense».
     */
    public LocalizedText ringNames(String ringName) {
        var values=new LinkedHashMap<String,String>();
        for(String tag:PRODUCT_LANGUAGES) values.put(tag,ringName!=null?ringName:messages.format("dashboard.noRing",Map.of(),Locale.forLanguageTag(tag)));
        return new LocalizedText(values,context.config().club().defaultLocale());
    }
    public String instructorName(ClassSession c,boolean staff) {
        int hours=context.config().get("bookings.showInstructorHoursBefore",Integer.class);
        if(!staff && hours!=0 && clock.instant().isBefore(c.startsAt().minusSeconds(hours*3600L))) return null;
        return String.join(", ",context.catalog().instructors().stream().filter(i -> c.instructorIds().contains(i.id())).map(SchedulingCatalog.Resource::name).toList());
    }
    public Map<String,Object> session(ClassSession c,boolean member,List<String> inconsistencyIds) {
        if(member && c.state()!=ClassState.ACTIVE && c.state()!=ClassState.FINISHED) throw new ApiException(ErrorCode.NOT_FOUND);
        var out=new LinkedHashMap<String,Object>();
        out.put("id",c.id()); out.put("date",c.date()); out.put("startTime",c.startTime()); out.put("endTime",c.endTime());
        out.put("levelIds",c.levelIds()); out.put("displayDescription",description(c,LocaleContext.current())); out.put("state",c.state()); out.put("capacity",c.capacity());
        if(member) {
            out.put("ring",ring(c));
            out.put("instructorName",instructorName(c,false)); out.put("freeSeats",Math.max(0,c.capacity()-c.counters().booked()));
            if(enabled(Module.WAITLIST)) out.put("waiting",c.counters().waiting());
        } else {
            out.put("weekId",c.weekId()); out.put("startsAt",c.startsAt()); out.put("endsAt",c.endsAt()); out.put("ringId",c.ringId()); out.put("instructorIds",c.instructorIds());
            out.put("capacityMode",c.capacityMode()); out.put("description",c.description());
            out.put("counters",Map.of("booked",c.counters().booked(),"waiting",enabled(Module.WAITLIST)?c.counters().waiting():0));
            out.put("atRisk",risk(c).atRisk()); out.put("riskExempt",c.risk().exempt()); out.put("cancellation",c.cancellation()); out.put("origin",c.origin());
            if(enabled(Module.COURSES)) out.put("placementId",c.placementId()); if(RingBlockService.role("ADMIN")) out.put("notes",c.notes());
            out.put("version",c.version()); out.put("inconsistencyIds",inconsistencyIds);
        }
        return out;
    }
    /**
     * `GET /class-sessions/{id}`: the member projection, or the staff one plus `instructorNames[]` (in `instructorIds`
     * order) and `ring {id, name, color}` (`null` without a ring), which the drawer on 23 and screen 21 show (E5-T15).
     * Only the detail pays the catalog read; the calendar and list rows keep the ids.
     */
    public Map<String,Object> detail(ClassSession c) {
        if(member()) return session(c,true,List.of());
        var out=session(c,false,List.of());
        var names=new HashMap<String,String>(); context.catalog().instructors().forEach(i -> names.put(i.id(),i.name()));
        out.put("instructorNames",c.instructorIds().stream().map(id -> names.getOrDefault(id,"")).toList());
        out.put("ring",ring(c));
        return out;
    }
    private Map<String,Object> ring(ClassSession c) {
        return catalogs.rings().stream().filter(r -> r.id().equals(c.ringId())).map(r -> Map.<String,Object>of("id",r.id(),"name",r.name(),"color",r.color())).findFirst().orElse(null);
    }
    public boolean member() { return !RingBlockService.role("ADMIN") && !RingBlockService.role("INSTRUCTOR"); }
    public boolean visible(RingBlock b) { return b.reason()!=RingBlockReason.ACTIVITY || enabled(Module.ACTIVITIES); }
    public Map<String,Object> block(RingBlock b,boolean member) {
        var from=b.from().atZone(zone()); var to=b.to().atZone(zone()); var out=new LinkedHashMap<String,Object>();
        out.put("id",b.id()); out.put("ringId",b.ringId()); out.put("from",b.from()); out.put("to",b.to()); out.put("date",from.toLocalDate());
        out.put("fromLocal",from.toLocalTime().toString()); out.put("toLocal",to.toLocalTime().toString()); out.put("kind",b.kind()); out.put("reason",b.reason());
        out.put("activityId",b.activityId()); out.put("activityTitle",b.activityId()==null?null:activities.titles(List.of(b.activityId()),LocaleContext.current()).get(b.activityId()));
        if(!member) { out.put("note",b.note()); out.put("createdByName",identities.displayName(b.createdByAccountId())); }
        out.put("state",b.state()); out.put("version",b.version()); return out;
    }
}
