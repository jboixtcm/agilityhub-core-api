package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.application.SignupIdentityService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import java.time.format.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SchedulingNotifications {
    private record Student(String id,String memberId,String dogId) { }
    private final ClassSessionService sessions; private final ClassBookingsPort bookings; private final SchedulingRecipients census;
    private final PlanningCatalogAccess catalogs; private final SignupIdentityService identities; private final NotificationAccounts accounts;
    private final SystemNotificationService notifications; private final PlanningContext context; private final SessionProjection projection; private final IcuMessageSource messages;
    public SchedulingNotifications(ClassSessionService sessions,ClassBookingsPort bookings,SchedulingRecipients census,PlanningCatalogAccess catalogs,
            SignupIdentityService identities,NotificationAccounts accounts,SystemNotificationService notifications,PlanningContext context,SessionProjection projection,IcuMessageSource messages) {
        this.sessions=sessions; this.bookings=bookings; this.census=census; this.catalogs=catalogs; this.identities=identities; this.accounts=accounts;
        this.notifications=notifications; this.context=context; this.projection=projection; this.messages=messages;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    @SuppressWarnings("unchecked")
    public void deliver(String eventId,SchedulingEvent event) {
        try(var tenant=TenantContext.open(event.clubId())) {
            boolean cancelled=event.kind()==SchedulingEvent.Kind.ClassCancelledByClub; var payload=event.payload();
            if(cancelled && "RISK_REVIEW".equals(Objects.toString(payload.get("reason")))) return;
            var diff=(Map<String,Object>)payload.getOrDefault("diff",Map.of());
            if(!cancelled && (((Number)payload.getOrDefault("bookedCount",0)).intValue()==0 || Collections.disjoint(diff.keySet(),Set.of("startTime","ringId","instructorIds")))) return;
            var students=new ArrayList<Student>();
            if(cancelled) {
                for(var b:(List<Map<String,Object>>)payload.getOrDefault("affected",List.of())) students.add(new Student(b.get("bookingId").toString(),b.get("memberId").toString(),b.get("dogId").toString()));
                for(var w:bookings.waitlistEntries((List<String>)payload.getOrDefault("waitlistIds",List.of()))) students.add(new Student(w.entryId(),w.memberId(),w.dogId()));
                if(students.isEmpty()) return;
            } else bookings.activeBookings(event.aggregateId()).forEach(b -> students.add(new Student(b.bookingId(),b.memberId(),b.dogId())));
            var c=sessions.require(event.aggregateId()); String code=cancelled?"N-08a":"N-08b";
            notifyStudents(eventId,c,code,students,payload,diff,null);
            var instructorIds=new HashSet<>(c.instructorIds());
            if(diff.get("instructorIds") instanceof Map<?,?> change && change.get("before") instanceof List<?> previous) previous.forEach(i -> instructorIds.add(i.toString()));
            var instructorAccounts=new HashSet<String>();
            for(String memberId:catalogs.instructorMembers(instructorIds)) census.member(memberId).map(SchedulingRecipients.Member::accountId).ifPresent(instructorAccounts::add);
            for(String accountId:instructorAccounts) {
                var account=accounts.find(accountId).orElse(null); if(account==null) continue;
                var variables=variables(c,account.locale(),payload,diff); variables.put("dog_name","");
                notifications.appOnce(eventId+":staff:"+accountId+":app",code,accountId,variables);
                if(cancelled) notifications.sendOnce(eventId+":staff:"+accountId+":email",code,accountId,variables);
            }
            if(cancelled) for(String admin:identities.admins()) {
                var account=accounts.find(admin).orElse(null); if(account==null) continue;
                notifications.appOnce(eventId+":staff:"+admin+":app",code,admin,variables(c,account.locale(),payload,diff));
            }
        }
    }
    /**
     * S15 R-15-12 / §8: N-08a of a class cancelled by the risk review goes to the affected registrants only (the staff get
     * N-17), with `admin_text` = `scheduling.autoCancel.text` in each recipient's language. Called by the
     * `notifications.N-17` handler of `ClassAutoCancelled`; `notifications.N-08a` keeps ignoring RISK_REVIEW.
     */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    @SuppressWarnings("unchecked")
    public void autoCancelled(String eventId,Map<String,Object> payload,String classId,int minDogs) {
        var students=new ArrayList<Student>();
        for(var b:(List<Map<String,Object>>)payload.getOrDefault("affected",List.of())) students.add(new Student(b.get("bookingId").toString(),b.get("memberId").toString(),b.get("dogId").toString()));
        for(var w:bookings.waitlistEntries((List<String>)payload.getOrDefault("waitlistIds",List.of()))) students.add(new Student(w.entryId(),w.memberId(),w.dogId()));
        if(students.isEmpty()) return;
        notifyStudents(eventId,sessions.require(classId),"N-08a",students,payload,Map.of(),minDogs);
    }
    private void notifyStudents(String eventId,ClassSession c,String code,List<Student> students,Map<String,Object> payload,Map<String,Object> diff,Integer autoMinDogs) {
        for(var student:students) {
            var member=census.member(student.memberId()).orElse(null); if(member==null) continue;
            var account=member.accountId()==null?null:accounts.find(member.accountId()).orElse(null);
            String locale=account==null?member.locale():account.locale(); var variables=variables(c,locale,payload,diff);
            if(autoMinDogs!=null) {
                variables.put("admin_text",messages.format("scheduling.autoCancel.text",Map.of("minDogs",autoMinDogs),locale(locale)));
                variables.put("action","CHANGE_CLASS"); // the ClassAutoCancelled payload carries no `reason`
            }
            variables.put("dog_name",census.dog(student.dogId()).map(SchedulingRecipients.Dog::name).orElse(""));
            String key=eventId+":"+student.id();
            if(account!=null) notifications.appOnce(key+":app",code,account.id(),variables);
            if(member.email()!=null) {
                if(account!=null && member.email().equals(account.email())) notifications.sendOnce(key+":email",code,account.id(),variables);
                else notifications.sendApplicantOnce(key+":email",code,member.email(),locale,variables);
            }
            if(!member.phones().isEmpty()) notifications.smsIntentOnce(key+":sms",code,member.accountId(),locale,member.phones(),
                    SchedulingSms.compact(messages.format("notif."+code+".sms",variables,locale(locale))),projection.enabled(Module.SMS),variables);
        }
    }
    private Locale locale(String tag) { return tag!=null && Set.of("ca","es","en").contains(tag)?Locale.forLanguageTag(tag):Locale.forLanguageTag(context.config().club().defaultLocale()); }
    private Map<String,Object> variables(ClassSession c,String language,Map<String,Object> payload,Map<String,Object> diff) {
        var locale=locale(language); var values=new LinkedHashMap<String,Object>(); values.put("club_name",context.config().club().name()); values.put("dog_name","");
        values.put("class_date",c.startsAt().atZone(projection.zone()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)));
        values.put("class_time",c.startTime()); values.put("class_description",projection.description(c,locale)); values.put("admin_text",Objects.toString(payload.get("adminText"),""));
        var changes=new ArrayList<String>();
        for(String field:List.of("startTime","ringId","instructorIds")) if(diff.get(field) instanceof Map<?,?> change) {
            changes.add(messages.format("scheduling.change."+field,Map.of("before",display(field,change.get("before")),"after",display(field,change.get("after"))),locale));
        }
        values.put("changes",String.join(" · ",changes)); values.put("entityId",c.id()); values.put("action",payload.containsKey("reason")?"CHANGE_CLASS":"OPEN_BOOKING"); return values;
    }
    private String display(String field,Object value) {
        if(field.equals("startTime")) return Objects.toString(value,"");
        var catalog=context.catalog();
        if(field.equals("ringId")) return catalog.rings().stream().filter(r -> r.id().equals(value)).map(SchedulingCatalog.Resource::name).findFirst().orElse("—");
        if(value instanceof List<?> ids) return String.join(", ",catalog.instructors().stream().filter(i -> ids.contains(i.id())).map(SchedulingCatalog.Resource::name).toList());
        return "";
    }
    @Configuration(proxyBeanMethods=false)
    static class Consumers {
        @Bean("notifications.N-08a") DomainEventHandler<SchedulingEvent> cancellation(SchedulingNotifications service) { return handler("ClassCancelledByClub",service); }
        @Bean("notifications.N-08b") DomainEventHandler<SchedulingEvent> edition(SchedulingNotifications service) { return handler("ClassSessionUpdated",service); }
        private DomainEventHandler<SchedulingEvent> handler(String type,SchedulingNotifications service) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; } public Class<SchedulingEvent> eventClass() { return SchedulingEvent.class; }
                public void handle(String id,SchedulingEvent event) { service.deliver(id,event); }
            };
        }
    }
}
