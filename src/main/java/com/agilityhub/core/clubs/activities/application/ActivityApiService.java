package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.scheduling.application.RingBlockService;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.application.contract.ApiContracts.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.util.*;
import static com.agilityhub.core.clubs.activities.application.ActivityProjection.object;

/** HTTP-independent orchestration of explicit activity views. */
@Service
public class ActivityApiService {
    private final ActivityService service; private final ActivityLifecycleService lifecycle; private final ActivityRegistrationService registrations;
    private final ActivityProjection projection; private final ActivityQueryService queries; private final ActivityNotifications notifications;
    private final SchedulingRecipients recipients; private final ListEngine lists;
    public ActivityApiService(ActivityService service,ActivityLifecycleService lifecycle,ActivityRegistrationService registrations,ActivityProjection projection,
            ActivityQueryService queries,ActivityNotifications notifications,SchedulingRecipients recipients,ListEngine lists) {
        this.service=service; this.lifecycle=lifecycle; this.registrations=registrations; this.projection=projection; this.queries=queries; this.notifications=notifications; this.recipients=recipients; this.lists=lists;
    }
    public Map<String,Object> activity(String id,boolean admin) { return projection.activity(service.require(id),admin); }
    public Map<String,Object> create(Map<String,String> title,ActivityType type) { return projection.activity(service.create(title,type),true); }
    public Map<String,Object> patch(String id,long version,Map<String,Object> changes,RingBlockService.Options options) { return projection.activity(service.patch(id,version,changes,options),true); }
    public Map<String,Object> publish(String id,boolean notifyEmail,RingBlockService.Options options) { return projection.activity(lifecycle.publish(id,notifyEmail,options),true); }
    public Map<String,Object> unpublish(String id) { return projection.activity(lifecycle.unpublish(id),true); }
    public Map<String,Object> cancel(String id,ActivityCancellationReason reason,String text) { return projection.activity(lifecycle.cancel(id,reason,text),true); }
    public Map<String,Object> image(String id,String key,String name) { service.image(id,key,name); return object("image",projection.image(service.require(id).image())); }
    public Map<String,Object> document(String id,String key,String name) { var file=service.document(id,key,name); return projection.document(service.require(id).documents().stream().filter(d -> d.id().equals(file.id())).findFirst().orElseThrow()); }
    public void deleteFile(String id,String docId) { service.deleteFile(id,docId); }
    public Map<String,Object> conflicts(String id) {
        var result=lifecycle.conflicts(id); return object("conflicts",result.conflicts(),"trainingBookings",result.bookings().stream().map(b -> object("bookingId",b.id(),"ringId",b.ringId(),"from",b.from(),"to",b.to(),"memberName",b.memberName(),"dogName",b.dogName())).toList());
    }
    public Map<String,Object> preview(String id) {
        var a=service.require(id); var rows=registrations.registrations.live(id).stream().map(r -> {
            var m=recipients.member(r.memberId()).orElseThrow(); return object("registrationId",r.id(),"memberName",m.name(),"state",r.state(),"channels",notifications.channels(m.id(),m.email()!=null,service.context.enabled(com.agilityhub.core.platform.application.Module.SMS) && !m.phones().isEmpty(),"CLUB_CHANGES"),"phoneCount",m.phones().size());
        }).toList(); return object("registrations",rows,"activeCount",a.counters().active(),"waitingCount",a.counters().waiting());
    }
    public Map<String,Object> register(String id,boolean waitlist) { var r=registrations.register(id,waitlist); return projection.registration(r,service.require(r.activityId())); }
    public Map<String,Object> registration(String id,boolean staff) { var r=registrations.require(id,staff); return projection.registration(r,service.require(r.activityId())); }
    public String registrationActivityId(String id) { return registrations.activityOf(id); }
    public Map<String,Object> cancelRegistration(String id,String reason) { var r=registrations.cancel(id,reason); return projection.registration(r,service.require(r.activityId())); }
    public Map<String,Object> mine(String dogId) { return queries.mine(dogId); }
    public Map<String,Object> detail(String id) { return queries.detail(id); }
    public ListPage<Map<String,Object>> list(MultiValueMap<String,String> params) { return lists.list("activities",ActivityLists.params(params)); }
    public FilterValues facets(String field,MultiValueMap<String,String> params) { return lists.facets("activities",field,ActivityLists.params(params)); }
    /**
     * D7's registrations (S07 §6, E5-T20). The activity comes from the path only: a query `activityId` filter is INVALID_FILTER
     * (not x-filterable) and `appliedFilters` lists the query's filters. Every row carries its `registrationId` and the
     * `waitlistRank` computed now (R-07-08). The rank reads the row's own `state` (E5-T22, review E5-T20 #1): a row that the
     * page read as not WAITLISTED gets `null`, even when a promotion or a cancellation happened between the two reads. So
     * the page always reads `state`; the controller leaves it out again when `fields` did not ask for it.
     */
    public ListPage<Map<String,Object>> registrations(String id,MultiValueMap<String,String> params) {
        service.require(id);
        if(params.getOrDefault("filter",List.of()).stream().anyMatch(filter -> filter.startsWith("activityId:"))) throw ListDefinition.invalid();
        var input=new LinkedMultiValueMap<>(params); input.add("filter","activityId:eq:"+id);
        var fields=params.get("fields");
        if(fields!=null && fields.size()==1 && fields.getFirst()!=null && !ListQuery.csv(fields.getFirst()).contains("state")) input.set("fields",fields.getFirst()+",state");
        var page=lists.list("activity-registrations",input); var ranks=projection.waitlistRanks(id);
        var items=page.items().stream().map(row -> {
            var item=new LinkedHashMap<String,Object>(row); String registration=row.get("id").toString();
            item.put("registrationId",registration);
            item.put("waitlistRank","WAITLISTED".equals(String.valueOf(row.get("state")))?ranks.get(registration):null); return (Map<String,Object>)item;
        }).toList();
        return new ListPage<>(items,page.page(),page.size(),page.totalItems(),page.totalPages(),
                page.appliedFilters().stream().filter(filter -> !filter.field().equals("activityId")).toList());
    }
}
