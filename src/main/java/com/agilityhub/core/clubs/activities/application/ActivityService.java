package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.scheduling.application.RingBlockService;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ActivityService {
    final ActivityRepository activities; final ActivityContext context; final ActivityTransactions transactions;
    final ActivityEvents events; final ActivityAudit audit; final RingBlockService blocks;
    private final ActivityRegistrationService registrations; private final AttachmentService attachments; private final ObjectMapper mapper;
    public ActivityService(ActivityRepository activities,ActivityContext context,ActivityTransactions transactions,ActivityEvents events,
            ActivityAudit audit,RingBlockService blocks,ActivityRegistrationService registrations,AttachmentService attachments,ObjectMapper mapper) {
        this.activities=activities; this.context=context; this.transactions=transactions; this.events=events; this.audit=audit; this.blocks=blocks;
        this.registrations=registrations; this.attachments=attachments; this.mapper=mapper;
    }
    public Activity require(String id) { context.require(); return activities.require(id); }
    public Activity create(Map<String,String> title,ActivityType type) {
        context.require();
        return transactions.write(() -> {
            context.catalogs.lockReferences(); var a=new ActivityEdit(); var now=context.clock.instant();
            a.id=UUID.randomUUID().toString(); a.clubId=TenantContext.require(); a.title=context.text(title,"title",80,true); a.type=type;
            a.slug=SlugGenerator.generate(a.title.values().get(context.config().club().defaultLocale()),slug -> activities.findBySlug(slug).isPresent());
            a.location=new Activity.Location(true,null,null,null); a.ringIds=List.of(); a.levelIds=List.of(); a.documents=List.of(); a.priceTiers=List.of(); a.ringBlockIds=List.of();
            a.state=ActivityState.DRAFT; a.counters=new Activity.Counters(0,0); a.visibility="MEMBERS"; a.version=0L;
            a.createdAt=now; a.updatedAt=now; a.createdByAccountId=context.actor(); a.updatedByAccountId=context.actor(); context.validate(a,false);
            var saved=activities.insert(a.snapshot()); updated(null,saved,Map.of("created",true)); return saved;
        });
    }
    @SuppressWarnings("unchecked")
    public Activity patch(String id,long version,Map<String,Object> patch,RingBlockService.Options options) {
        context.require();
        return transactions.write(() -> {
            context.catalogs.lockReferences(); var before=activities.lock(id); if(before.version()!=version) throw new ApiException(ErrorCode.STALE_VERSION);
            if((before.state()==ActivityState.CANCELLED || before.state()==ActivityState.FINISHED) && patch.keySet().stream().anyMatch(k -> !k.equals("internalNotes"))) throw new ApiException(ErrorCode.INVALID_STATE);
            var a=new ActivityEdit(before);
            for(var entry:patch.entrySet()) {
                String field=entry.getKey(); Object value=entry.getValue();
                switch(field) {
                    case "title" -> a.title=context.text((Map<String,String>)value,field,80,true);
                    case "typeLabel" -> a.typeLabel=context.text((Map<String,String>)value,field,30,false);
                    case "shortDescription" -> a.shortDescription=context.text((Map<String,String>)value,field,160,false);
                    case "longDescription" -> {
                        var text=context.text((Map<String,String>)value,field,20000,false);
                        if(text==null) a.longDescription=null;
                        else { var sanitized=new LinkedHashMap<String,String>(); text.values().forEach((key,html) -> sanitized.put(key,HtmlSanitizer.sanitize(html))); a.longDescription=new LocalizedText(sanitized,text.defaultLocale()); }
                    }
                    case "type" -> { if(value==null) ActivityRules.invalid(field); a.type=ActivityType.valueOf(value.toString()); }
                    case "location" -> { if(value==null) ActivityRules.invalid(field); a.location=mapper.convertValue(value,Activity.Location.class); }
                    case "ringIds" -> { if(value==null || ((List<?>)value).stream().anyMatch(Objects::isNull)) ActivityRules.invalid(field); a.ringIds=List.copyOf((List<String>)value); }
                    case "levelIds" -> { if(value==null || ((List<?>)value).stream().anyMatch(Objects::isNull)) ActivityRules.invalid(field); a.levelIds=List.copyOf((List<String>)value); }
                    case "date" -> a.date=date(value);
                    case "startTime" -> a.startTime=(String)value;
                    case "endTime" -> a.endTime=(String)value;
                    case "registrationFrom" -> a.registrationFrom=date(value);
                    case "registrationTo" -> a.registrationTo=date(value);
                    case "ringBlockWindow" -> a.ringBlockWindow=mapper.convertValue(value,Activity.RingBlockWindow.class);
                    case "minPlaces" -> a.minPlaces=value==null?null:((Number)value).intValue();
                    case "maxPlaces" -> a.maxPlaces=value==null?null:((Number)value).intValue();
                    case "waitlistEnabled" -> a.waitlistEnabled=Boolean.TRUE.equals(value);
                    case "slug" -> { SlugGenerator.editable(before.slug(),(String)value,before.publishedAt()!=null); a.slug=(String)value; }
                    case "visibility" -> { if(!"MEMBERS".equals(value)) ActivityRules.invalid(field); }
                    case "priceTiers" -> { if(!(value instanceof List<?> list) || !list.isEmpty()) ActivityRules.invalid(field); }
                    case "internalNotes" -> a.internalNotes=(String)value;
                    default -> ActivityRules.invalid(field);
                }
            }
            if(a.maxPlaces!=null && a.maxPlaces<a.counters.active()) throw new ApiException(ErrorCode.CAPACITY_BELOW_REGISTRATIONS);
            if(!before.slug().equals(a.slug) && activities.findBySlug(a.slug).isPresent()) throw new ApiException(ErrorCode.DUPLICATE_SLUG);
            if(before.state()!=ActivityState.FINISHED && before.state()!=ActivityState.CANCELLED) context.validate(a,before.state()==ActivityState.PUBLISHED);
            var diff=diff(before,a.snapshot());
            if(a.state==ActivityState.PUBLISHED && !Collections.disjoint(diff.keySet(),Set.of("date","startTime","endTime","ringIds","ringBlockWindow","location"))) sync(a,options);
            if(a.state==ActivityState.PUBLISHED && (a.maxPlaces==null && before.maxPlaces()!=null || a.maxPlaces!=null && before.maxPlaces()!=null && a.maxPlaces>before.maxPlaces())) registrations.promote(a);
            var saved=save(a,before.version()); registrations.refreshStartsAt(saved); updated(before,saved,diff); return saved;
        });
    }
    private LocalDate date(Object value) { return value==null?null:value instanceof LocalDate date?date:LocalDate.parse(value.toString()); }
    void sync(ActivityEdit a,RingBlockService.Options options) {
        blocks.syncForActivity(context.blockRequest(a.snapshot()),options); a.ringBlockIds=blocks.activityBlockIds(a.id);
    }
    Activity save(ActivityEdit a,long expected) {
        a.version=expected+1; a.updatedAt=context.clock.instant(); a.updatedByAccountId=context.actor();
        try { return activities.update(a.snapshot(),expected); }
        catch(org.springframework.dao.DuplicateKeyException duplicate) { throw new ApiException(ErrorCode.DUPLICATE_SLUG); }
    }
    void updated(Activity before,Activity after,Map<String,Object> diff) {
        events.publish(ActivityEvent.Kind.ActivityUpdated,after.id(),Map.of("activityId",after.id(),"diff",diff,"state",after.state(),"registrantCount",after.counters().active()+after.counters().waiting()));
        audit.updated(before,after);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> diff(Activity before,Activity after) {
        Map<String,Object> left=mapper.convertValue(before,Map.class),right=mapper.convertValue(after,Map.class),diff=new LinkedHashMap<>();
        var fields=new LinkedHashSet<>(left.keySet()); fields.addAll(right.keySet());
        for(String field:fields) if(!Objects.equals(left.get(field),right.get(field))) {
            var change=new LinkedHashMap<String,Object>(); change.put("before",left.get(field)); change.put("after",right.get(field)); diff.put(field,change);
        }
        return diff;
    }
    public AttachmentService.File image(String id,String key,String name) {
        return file(id,key,name,true);
    }
    public AttachmentService.File document(String id,String key,String name) { return file(id,key,name,false); }
    private AttachmentService.File file(String id,String key,String name,boolean image) {
        context.require();
        return transactions.write(() -> {
            var before=activities.lock(id); editable(before); var a=new ActivityEdit(before);
            if(!image && a.documents.stream().noneMatch(d -> d.fileKey().equals(key)) && a.documents.size()>=10) throw new ApiException(ErrorCode.TOO_MANY_DOCUMENTS);
            var file=attachments.claim(key,image?"ACTIVITY_IMAGE":"ACTIVITY_DOCUMENT",id);
            if(image) a.image=new Activity.Image(file.id(),key,name,file.mimeType(),file.sizeBytes());
            else { var docs=new ArrayList<>(a.documents); docs.removeIf(d -> d.fileKey().equals(key)); docs.add(new Activity.ActivityDocument(file.id(),key,name,file.mimeType(),file.sizeBytes(),file.uploadedAt())); a.documents=List.copyOf(docs); }
            var saved=save(a,before.version()); updated(before,saved,diff(before,saved)); return file;
        });
    }
    public void deleteFile(String id,String docId) {
        context.require(); transactions.write(() -> {
            var before=activities.lock(id); editable(before); var a=new ActivityEdit(before);
            if(docId==null) a.image=null;
            else { if(a.documents.stream().noneMatch(d -> d.id().equals(docId))) throw new ApiException(ErrorCode.NOT_FOUND); a.documents=a.documents.stream().filter(d -> !d.id().equals(docId)).toList(); }
            var saved=save(a,before.version()); updated(before,saved,diff(before,saved)); return null;
        });
    }
    private void editable(Activity a) { if(a.state()==ActivityState.CANCELLED || a.state()==ActivityState.FINISHED) throw new ApiException(ErrorCode.INVALID_STATE); }
}
