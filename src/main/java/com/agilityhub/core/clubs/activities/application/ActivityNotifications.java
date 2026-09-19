package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import java.time.format.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ActivityNotifications {
    private final ActivityRepository activities; private final ActivityRegistrationRepository registrations; private final ActivityAudienceService audience;
    private final ActivityContext context; private final ActivityProjection projection; private final SchedulingRecipients recipients;
    private final NotificationAccounts accounts; private final SystemNotificationService notifications;
    public ActivityNotifications(ActivityRepository activities,ActivityRegistrationRepository registrations,ActivityAudienceService audience,
            ActivityContext context,ActivityProjection projection,SchedulingRecipients recipients,NotificationAccounts accounts,SystemNotificationService notifications) {
        this.activities=activities; this.registrations=registrations; this.audience=audience; this.context=context; this.projection=projection;
        this.recipients=recipients; this.accounts=accounts; this.notifications=notifications;
    }
    public List<String> channels(String memberId,boolean email,boolean sms,String category) {
        var preferences=context.members.notificationPreferences(memberId); Object raw=preferences.get(category);
        Map<?,?> configured=raw instanceof Map<?,?> map?map:Map.of();
        var result=new ArrayList<String>(); result.add("APP");
        if(email && !Boolean.FALSE.equals(configured.get("email"))) result.add("EMAIL");
        if(sms && !Boolean.FALSE.equals(configured.get("sms"))) result.add("SMS");
        return result;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    @SuppressWarnings("unchecked")
    public void deliver(String eventId,ActivityEvent event) {
        try(var tenant=TenantContext.open(event.clubId())) {
            if(!context.enabled(Module.ACTIVITIES)) return;
            var payload=event.payload(); String activityId=Objects.toString(payload.get("activityId"),event.aggregateId());
            var a=activities.require(activityId); String code; boolean email=false,sms=false; String category="OPERATIONAL"; var members=new LinkedHashSet<String>();
            switch(event.kind()) {
                case ActivityPublished -> { code="N-32a"; members.addAll(audience.admittedMemberIds(a)); email=Boolean.TRUE.equals(payload.get("notifyEmail")); category="CLUB_NEWS"; }
                case ActivityCancelled -> {
                    code="N-32c"; email=true; sms=true; category="CLUB_CHANGES";
                    for(var item:(List<Map<String,Object>>)payload.getOrDefault("affected",List.of())) members.add(item.get("memberId").toString());
                }
                case ActivityRegistrationChanged -> {
                    String reason=Objects.toString(payload.get("cancelReason"),"");
                    if(Set.of("MEMBER_LEFT","ACTIVITY_CANCELLED").contains(reason)) return;
                    code="N-32b"; members.add(payload.get("memberId").toString());
                    if("BACKOFFICE".equals(Objects.toString(payload.get("origin")))) { email=true; sms=true; category="CLUB_CHANGES"; }
                }
                case ActivityUpdated -> {
                    var diff=(Map<String,Object>)payload.getOrDefault("diff",Map.of());
                    if(((Number)payload.getOrDefault("registrantCount",0)).intValue()==0 || Collections.disjoint(diff.keySet(),Set.of("date","startTime","endTime","location","ringIds"))) return;
                    code="N-32d"; email=true; sms=true; category="CLUB_CHANGES"; registrations.forActivity(a.id()).stream().filter(r -> !r.registeredAt().isAfter(event.occurredAt()) && (r.cancelledAt()==null || !r.cancelledAt().isBefore(event.occurredAt()))).forEach(r -> members.add(r.memberId()));
                }
                default -> { return; }
            }
            for(String memberId:members) {
                var member=recipients.member(memberId).orElse(null); if(member==null) continue;
                var account=member.accountId()==null?null:accounts.find(member.accountId()).orElse(null);
                String tag=account==null?member.locale():account.locale(); var locale=Locale.forLanguageTag(tag==null?context.config().club().defaultLocale():tag);
                if(!context.messages.supports(locale)) locale=Locale.forLanguageTag(context.config().club().defaultLocale());
                Map<String,Object> values;
                try(var ignored=LocaleContext.open(locale)) {
                    values=new LinkedHashMap<>(); values.put("activity_title",projection.title(a)); values.put("club_name",context.config().club().name());
                    values.put("date",a.date()==null?"":a.date().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)));
                    values.put("admin_text",Objects.toString(payload.get("adminText"),"")); values.put("state",Objects.toString(payload.get("state"),""));
                    values.put("changes",changes(a,(Map<String,Object>)payload.getOrDefault("diff",Map.of()),locale));
                    values.put("entityId",a.id()); if(!code.equals("N-32c")) values.put("action","OPEN_ACTIVITY");
                    values.put("title",context.messages.format("notif."+code+".title",values,locale));
                    values.put("body",context.messages.format("notif."+code+".body",values,locale));
                }
                String key=eventId+":"+memberId; var channels=channels(memberId,email,sms,category);
                if(account!=null) notifications.appOnce(key+":app",code,account.id(),values);
                if(channels.contains("EMAIL") && member.email()!=null) {
                    if(account!=null && member.email().equals(account.email())) notifications.sendOnceLocalized(key+":email",code,account.id(),locale.toLanguageTag(),values);
                    else notifications.sendApplicantOnce(key+":email",code,member.email(),locale.toLanguageTag(),values);
                }
                if(channels.contains("SMS") && !member.phones().isEmpty()) notifications.smsIntentOnce(key+":sms",code,member.accountId(),locale.toLanguageTag(),member.phones(),
                        ActivitySms.compact(context.messages.format("notif."+code+".sms",values,locale)),context.enabled(Module.SMS),values);
            }
        }
    }
    private String changes(Activity activity,Map<String,Object> diff,Locale locale) {
        var result=new ArrayList<String>();
        for(String field:List.of("date","startTime","endTime","location","ringIds")) if(diff.containsKey(field)) {
            String value=switch(field) { case "date" -> Objects.toString(activity.date(),""); case "startTime" -> Objects.toString(activity.startTime(),""); case "endTime" -> Objects.toString(activity.endTime(),""); default -> projection.place(activity); };
            result.add(context.messages.format("activities.change."+field,Map.of("value",Objects.toString(value,"")),locale));
        }
        return String.join(" · ",result);
    }
    @Configuration(proxyBeanMethods=false)
    static class Consumers {
        @Bean("notifications.N-32a") DomainEventHandler<ActivityEvent> published(ActivityNotifications service) { return handler("ActivityPublished",service); }
        @Bean("notifications.N-32b") DomainEventHandler<ActivityEvent> registration(ActivityNotifications service) { return handler("ActivityRegistrationChanged",service); }
        @Bean("notifications.N-32c") DomainEventHandler<ActivityEvent> cancelled(ActivityNotifications service) { return handler("ActivityCancelled",service); }
        @Bean("notifications.N-32d") DomainEventHandler<ActivityEvent> updated(ActivityNotifications service) { return handler("ActivityUpdated",service); }
        private DomainEventHandler<ActivityEvent> handler(String type,ActivityNotifications service) { return new DomainEventHandler<>() {
            public String eventType() { return type; } public Class<ActivityEvent> eventClass() { return ActivityEvent.class; }
            public void handle(String id,ActivityEvent event) { service.deliver(id,event); }
        }; }
    }
}
