package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Explicit application views; public projections never derive from a registration or staff response. */
@Service
public class ActivityProjection {
    final ActivityContext context; private final AttachmentService attachments; private final ActivityRepository activities;
    public ActivityProjection(ActivityContext context,AttachmentService attachments,ActivityRepository activities) { this.context=context; this.attachments=attachments; this.activities=activities; }
    public static Map<String,Object> object(Object... pairs) {
        var result=new LinkedHashMap<String,Object>(); for(int i=0;i<pairs.length;i+=2) result.put(pairs[i].toString(),pairs[i+1]); return result;
    }
    public String text(LocalizedText value) { return value==null?null:value.withDefaultLocale(context.config().club().defaultLocale()).resolve(LocaleContext.current()).value(); }
    public String title(Activity a) { return text(a.title()); }
    public String type(Activity a) { return type(a.type(),a.typeLabel()); }
    /** `typeDisplay`: the club's free label in the reader's locale, otherwise the product label of the enum (R-07-01). Shared with the D7 list. */
    public String type(ActivityType type,LocalizedText label) { return label==null?context.messages.format("activities.typeLabel."+type,Map.of(),LocaleContext.current()):text(label); }
    private List<PlanningCatalogAccess.RingView> rings(Activity a) { return context.catalogs.rings().stream().filter(r -> a.ringIds().contains(r.id())).toList(); }
    public boolean allRings(Activity a) { return allRings(a.ringIds(),activeRingIds()); }
    public Set<String> activeRingIds() { return context.catalogs.rings().stream().filter(PlanningCatalogAccess.RingView::active).map(PlanningCatalogAccess.RingView::id).collect(java.util.stream.Collectors.toSet()); }
    /** Every active ring of the catalog (R-07-11 «totes les pistes»); the D7 list reads the catalog once per page. */
    public static boolean allRings(Collection<String> ringIds,Set<String> activeRingIds) { return !ringIds.isEmpty() && new HashSet<>(ringIds).equals(activeRingIds); }
    public String place(Activity a) {
        var all=new LinkedHashMap<String,String>(); var active=new LinkedHashMap<String,String>();
        context.catalogs.rings().forEach(r -> { all.put(r.id(),r.name()); if(r.active()) active.put(r.id(),r.name()); });
        return ActivityRows.place(a.location().atClub(),a.location().name(),a.ringIds(),active,all,context.messages.format("activities.allRings",Map.of(),LocaleContext.current()));
    }
    public Integer free(Activity a) { return a.maxPlaces()==null?null:Math.max(0,a.maxPlaces()-a.counters().active()); }
    public boolean waitlist(Activity a) { return a.waitlistEnabled() && context.enabled(Module.WAITLIST); }
    public boolean open(Activity a) { return a.state()==ActivityState.PUBLISHED && context.times(a).registrationOpen(context.clock.instant()); }
    private List<String> levelNames(Activity a) { return !context.levels()?List.of():context.catalogs.snapshot().levels().stream().filter(l -> a.levelIds().contains(l.id())).map(l -> text(l.name())).toList(); }
    private Object i18n(LocalizedText value) { return value==null?null:value.values(); }
    public String publicUrl(Activity a) {
        String website=context.clubs.websiteUrl(a.clubId());
        return website==null?null:context.config().get("activities.publicUrlTemplate",String.class).replace("{websiteUrl}",website.replaceAll("/+$", "")).replace("{slug}",a.slug());
    }
    public Map<String,Object> image(Activity.Image image) { return image==null?null:object("fileId",image.fileId(),"name",image.name(),"url",attachments.url(image.fileKey(),image.name())); }
    public Map<String,Object> document(Activity.ActivityDocument file) { return object("id",file.id(),"name",file.name(),"url",attachments.url(file.fileKey(),file.name())); }
    public Map<String,Object> activity(Activity a,boolean admin) {
        var times=context.times(a);
        var result=object("id",a.id(),"slug",a.slug(),"state",a.state(),"type",a.type(),"typeLabel",i18n(a.typeLabel()),"typeDisplay",type(a),"title",title(a),"titleI18n",i18n(a.title()),
                "shortDescription",text(a.shortDescription()),"shortDescriptionI18n",i18n(a.shortDescription()),"longDescriptionHtml",text(a.longDescription()),"longDescriptionI18n",i18n(a.longDescription()),
                "image",image(a.image()),"documents",a.documents().stream().map(this::document).toList(),"location",a.location(),"ringIds",a.ringIds(),
                "rings",rings(a).stream().map(r -> object("id",r.id(),"name",r.name(),"color",r.color())).toList(),"allRings",allRings(a),
                "date",a.date(),"startTime",a.startTime(),"endTime",a.endTime(),"startsAt",times.startsAt(),"endsAt",times.endsAt(),"ringBlockWindow",a.ringBlockWindow(),
                "registrationFrom",a.registrationFrom(),"registrationTo",a.registrationTo(),"registrationOpen",open(a),"minPlaces",a.minPlaces(),"maxPlaces",a.maxPlaces(),
                "levelIds",context.levels()?a.levelIds():List.of(),"levelNames",levelNames(a),"waitlistEnabled",waitlist(a),"visibility","MEMBERS","priceTiers",List.of(),
                "counters",a.counters(),"freeSeats",free(a),"belowMinimum",a.minPlaces()!=null && a.counters().active()<a.minPlaces(),"publicUrl",publicUrl(a),
                "ringBlockIds",a.ringBlockIds(),"publishedAt",a.publishedAt(),"cancellation",a.cancellation(),"version",a.version());
        if(admin) result.put("internalNotes",a.internalNotes());
        if(context.enabled(Module.COURSES)) result.put("placementIds",activities.placementIds(a.id()));
        return result;
    }
    public Map<String,Object> registeredActivity(Activity a) {
        return object("id",a.id(),"title",title(a),"startsAtLocal",local(context.times(a).startsAt()),"endsAtLocal",endsAtLocal(a),
                "startTime",a.startTime(),"endTime",a.endTime(),"placeLabel",place(a));
    }
    public String local(Instant instant) { return instant==null?null:instant.atZone(context.zone()).toLocalDateTime().toString(); }
    /**
     * S07 «Canvis» 24-09 (3): `null` for an activity without an end time. `endsAt` keeps the model's convention (the next day at
     * 00:00 local, for ordering, deadlines and the schedulers); the read models never show that made-up end.
     */
    public String endsAtLocal(Activity a) { return a.endTime()==null?null:local(context.times(a).endsAt()); }
    public Map<String,Object> registration(ActivityRegistration r,Activity a) {
        return object("id",r.id(),"activityId",a.id(),"memberId",r.memberId(),"state",r.state(),"origin",r.origin(),"position",r.position(),"activity",registeredActivity(a),
                "registeredAt",r.registeredAt(),"registeredBy",object("displayName",r.registeredBy().displayName(),"viaClub",r.registeredBy().impersonatedMemberId()!=null),
                "cancellableUntil",CancellationDeadline.deadline(context.deadlinePolicy(),r.state(),context.times(a),context.impersonated()),
                "cancellation",r.cancelReason()==null?null:object("reason",r.cancelReason(),"at",r.cancelledAt(),"byRole",r.cancelledBy().role()),
                "impersonation",r.registeredBy().impersonatedMemberId()==null?null:object("actorAccountId",r.registeredBy().accountId(),"memberId",r.registeredBy().impersonatedMemberId()));
    }
    public Map<String,Object> publicActivity(Activity a) {
        String base="/api/v1/public/"+context.config().club().slug()+"/activities/"+a.slug()+"/files/";
        return object("slug",a.slug(),"state",a.state(),"type",a.type(),"typeLabel",type(a),"title",title(a),"titleI18n",i18n(a.title()),
                "typeLabelI18n",i18n(a.typeLabel()),"shortDescriptionI18n",i18n(a.shortDescription()),"longDescriptionI18n",i18n(a.longDescription()),
                "shortDescription",text(a.shortDescription()),"longDescriptionHtml",text(a.longDescription()),"longDescriptionText",HtmlSanitizer.text(text(a.longDescription())),
                "imageUrl",a.image()==null?null:base+a.image().fileId(),"documents",a.documents().stream().map(d -> object("name",d.name(),"url",base+d.id())).toList(),
                "location",a.location(),"ringNames",rings(a).stream().map(PlanningCatalogAccess.RingView::name).toList(),"date",a.date(),"startTime",a.startTime(),"endTime",a.endTime(),
                "timeZone",context.zone().getId(),"registration",object("from",a.registrationFrom(),"to",a.registrationTo(),"open",open(a),"channel","APP"),
                "places",object("max",a.maxPlaces(),"free",free(a),"waitlist",waitlist(a)),"levels",levelNames(a),"publicUrl",publicUrl(a));
    }
}
