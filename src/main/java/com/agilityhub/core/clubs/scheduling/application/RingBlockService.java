package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingConflictPort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RingBlockService {
    public record Options(boolean cancelBookings,boolean cancelClasses,String adminText) { }
    public record Conflict(String ringId,String type,String id,Instant from,Instant to,String label,Integer bookedCount) { }
    public record Conflicts(List<Conflict> conflicts,List<TrainingConflictPort.Booking> bookings) { }
    private final RingBlockRepository blocks; private final ClassSessionRepository classes; private final PlanningContext context;
    private final SchedulingTransactions transactions; private final SchedulingEvents events; private final SchedulingAudit audit;
    private final ClassCancellationUseCase cancellations; private final TrainingConflictPort training; private final Clock clock;
    public RingBlockService(RingBlockRepository blocks,ClassSessionRepository classes,PlanningContext context,SchedulingTransactions transactions,
            SchedulingEvents events,SchedulingAudit audit,ClassCancellationUseCase cancellations,TrainingConflictPort training,Clock clock) {
        this.blocks=blocks; this.classes=classes; this.context=context; this.transactions=transactions; this.events=events; this.audit=audit;
        this.cancellations=cancellations; this.training=training; this.clock=clock;
    }
    public RingBlock require(String id) { return blocks.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public static boolean role(String role) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_"+role));
    }
    private void forcePermission(boolean force) { if(force && !role("ADMIN")) throw new ApiException(ErrorCode.FORBIDDEN); }
    public RingBlock create(String ring,Instant from,Instant to,RingBlockKind kind,RingBlockReason reason,String note,boolean cancelBookings) {
        forcePermission(cancelBookings);
        return transactions.write(() -> {
            validate(ring,from,to,kind,reason,false); lockSlots(ring,from,to);
            resolve(conflicts(ring,from,to,null,null),new Options(cancelBookings,false,null),events.actor());
            return insert(ring,from,to,kind,reason,note,null,events.actor());
        });
    }
    public RingBlock patch(String id,long version,Map<String,Object> patch,boolean cancelBookings) {
        forcePermission(cancelBookings);
        return transactions.write(() -> {
            var b=require(id); mutable(b); if(!b.from().isAfter(clock.instant())) throw new ApiException(ErrorCode.INVALID_STATE);
            if(b.version()!=version) throw new ApiException(ErrorCode.STALE_VERSION);
            var ring=(String)patch.getOrDefault("ringId",b.ringId()); var from=(Instant)patch.getOrDefault("from",b.from()); var to=(Instant)patch.getOrDefault("to",b.to());
            var kind=(RingBlockKind)patch.getOrDefault("kind",b.kind()); var reason=(RingBlockReason)patch.getOrDefault("reason",b.reason());
            var note=(String)patch.getOrDefault("note",b.note()); validate(ring,from,to,kind,reason,false); lockSlots(ring,from,to);
            resolve(conflicts(ring,from,to,id,null),new Options(cancelBookings,false,null),events.actor());
            var after=new RingBlock(b.id(),b.clubId(),ring,from,to,kind,reason,note,null,b.state(),null,null,b.version()+1,b.createdAt(),b.createdByAccountId(),clock.instant(),events.actor());
            blocks.update(after,version); changed(b,after); return after;
        });
    }
    public RingBlock cancel(String id) { return transactions.write(() -> { var b=require(id); mutable(b); return cancelBlock(b); }); }
    private void mutable(RingBlock b) {
        if(b.activityId()!=null) throw new ApiException(ErrorCode.RING_BLOCK_MANAGED_BY_ACTIVITY);
        if(b.state()!=RingBlockState.ACTIVE) throw new ApiException(ErrorCode.INVALID_STATE);
    }
    private RingBlock cancelBlock(RingBlock b) {
        var now=clock.instant(); var actor=events.actor();
        var after=new RingBlock(b.id(),b.clubId(),b.ringId(),b.from(),b.to(),b.kind(),b.reason(),b.note(),b.activityId(),RingBlockState.CANCELLED,
                now,actor,b.version()+1,b.createdAt(),b.createdByAccountId(),now,actor);
        blocks.update(after,b.version()); events.publish(SchedulingEvent.Kind.RingBlockCancelled,b.id(),Map.of("blockId",b.id())); audit.cancelledBlock(b,after); return after;
    }
    private RingBlock insert(String ring,Instant from,Instant to,RingBlockKind kind,RingBlockReason reason,String note,String activity,String actor) {
        var now=clock.instant(); var b=blocks.insert(new RingBlock(UUID.randomUUID().toString(),TenantContext.require(),ring,from,to,kind,reason,note,activity,RingBlockState.ACTIVE,
                null,null,0L,now,actor,now,actor));
        var payload=new LinkedHashMap<String,Object>(); payload.put("blockId",b.id()); payload.put("ringId",ring); payload.put("from",from); payload.put("to",to); payload.put("reason",reason);
        if(activity!=null) payload.put("activityId",activity);
        events.publish(SchedulingEvent.Kind.RingBlockCreated,b.id(),payload); audit.created(b); return b;
    }
    private void changed(RingBlock before,RingBlock after) {
        var diff=new LinkedHashMap<String,Object>();
        var left=List.of(before.ringId(),before.from(),before.to(),before.kind(),before.reason(),Objects.toString(before.note(),""));
        var right=List.of(after.ringId(),after.from(),after.to(),after.kind(),after.reason(),Objects.toString(after.note(),""));
        var keys=List.of("ringId","from","to","kind","reason","note");
        for(int i=0;i<keys.size();i++) if(!left.get(i).equals(right.get(i))) diff.put(keys.get(i),Map.of("before",left.get(i),"after",right.get(i)));
        events.publish(SchedulingEvent.Kind.RingBlockUpdated,after.id(),Map.of("blockId",after.id(),"diff",diff));
    }
    private void validate(String ring,Instant from,Instant to,RingBlockKind kind,RingBlockReason reason,boolean activity) {
        var config=context.config();
        if(kind==RingBlockKind.RESERVATION && !config.modules().contains(Module.FREE_TRAINING)) throw new ApiException(ErrorCode.MODULE_DISABLED);
        boolean valid=kind==RingBlockKind.RESERVATION ? Set.of(RingBlockReason.PRIVATE_CLASS,RingBlockReason.THERAPY,RingBlockReason.PREPARATION,RingBlockReason.OTHER).contains(reason)
                : reason==RingBlockReason.MAINTENANCE || reason==RingBlockReason.OTHER || activity && reason==RingBlockReason.ACTIVITY;
        if(!valid) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        if(context.catalog().rings().stream().noneMatch(r -> r.id().equals(ring) && r.active())) throw new ApiException(ErrorCode.NOT_FOUND);
        if(!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofMinutes(config.get("training.slotMinutes",Integer.class)))<0) throw new ApiException(ErrorCode.INVALID_TIME_RANGE);
        var zone=ZoneId.of(config.club().timeZone()); var start=from.atZone(zone); var end=to.atZone(zone);
        if(!start.toLocalDate().equals(end.toLocalDate())) throw new ApiException(ErrorCode.INVALID_TIME_RANGE);
        // S07 validates its own finer activity granularity; manual D4/24 blocks use classes.slotMinutes.
        if(!activity) {
            if(!from.isAfter(clock.instant()) || start.toLocalDate().isAfter(clock.instant().atZone(zone).toLocalDate().plusDays(config.get("ringBlocks.maxHorizonDays",Integer.class)))) throw new ApiException(ErrorCode.VALIDATION_ERROR);
            ClassSessionRules.times(start.toLocalDate(),start.toLocalTime(),end.toLocalTime(),config.get("classes.slotMinutes",Integer.class),context.opening(config));
        } else {
            var hours=context.opening(config).get(start.getDayOfWeek());
            if(hours==null || start.toLocalTime().isBefore(hours.open()) || end.toLocalTime().isAfter(hours.close())) throw new ApiException(ErrorCode.OUTSIDE_OPENING_HOURS);
        }
    }
    /**
     * R-09-13: a write that checks the ring's live training bookings `$inc`s the ring-slot sequence of every training grid
     * slot its range overlaps (S09 computes the grid; the range may span any number of days), the documents a booking of
     * those slots also touches: a concurrent booking and block conflict in Mongo and the retried side sees the other.
     */
    private void lockSlots(String ring,Instant from,Instant to) {
        if(context.config().modules().contains(Module.FREE_TRAINING)) training.lockSlots(ring,from,to);
    }
    private Conflicts conflicts(String ring,Instant from,Instant to,String exceptBlock,String exceptActivity) {
        var result=new ArrayList<Conflict>(); var catalog=context.catalog();
        classes.between(from,to).stream().filter(c -> Objects.equals(c.ringId(),ring) && (c.state()==ClassState.DRAFT || c.state()==ClassState.ACTIVE)).forEach(c ->
                result.add(new Conflict(ring,"CLASS",c.id(),c.startsAt(),c.endsAt(),context.descriptions().resolve(c.description(),c.levelIds(),catalog,LocaleContext.current()),c.counters().booked())));
        blocks.between(from,to).stream().filter(b -> b.ringId().equals(ring) && !b.id().equals(exceptBlock) && (exceptActivity==null || !exceptActivity.equals(b.activityId())))
                .forEach(b -> result.add(new Conflict(ring,"RING_BLOCK",b.id(),b.from(),b.to(),b.reason().name(),null)));
        var booked=context.config().modules().contains(Module.FREE_TRAINING) ? training.findActiveBookings(ring,from,to) : List.<TrainingConflictPort.Booking>of();
        return new Conflicts(result,booked);
    }
    private void resolve(Conflicts conflicts,Options options,String actor) {
        var remaining=conflicts.conflicts().stream().filter(c -> !options.cancelClasses() || !c.type().equals("CLASS")).toList();
        if(!remaining.isEmpty()) throw new ApiException(ErrorCode.RING_BLOCK_CONFLICT,Map.of("conflicts",remaining));
        if(!conflicts.bookings().isEmpty() && !options.cancelBookings()) throw new ApiException(ErrorCode.RING_HAS_BOOKINGS,Map.of("bookings",conflicts.bookings()));
        for(var c:conflicts.conflicts()) cancellations.cancel(c.id(),classes.findById(c.id()).orElseThrow().state()==ClassState.DRAFT ? ClassCancellationReason.DELETED : ClassCancellationReason.ACTIVITY,options.adminText(),actor);
        if(!conflicts.bookings().isEmpty()) training.cancelByClub(conflicts.bookings().stream().map(TrainingConflictPort.Booking::id).toList(),"RING_BLOCK");
    }
    public Conflicts conflictsFor(ActivityBlockRequest request) {
        var conflicts=new ArrayList<Conflict>(); var bookings=new ArrayList<TrainingConflictPort.Booking>();
        for(String ring:new LinkedHashSet<>(request.ringIds())) { var found=conflicts(ring,request.from(),request.to(),null,request.activityId()); conflicts.addAll(found.conflicts()); bookings.addAll(found.bookings()); }
        return new Conflicts(conflicts,bookings);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void syncForActivity(ActivityBlockRequest request,Options options) {
        if(!context.config().modules().contains(Module.ACTIVITIES)) throw new ApiException(ErrorCode.MODULE_DISABLED);
        context.lockReferences(); forcePermission(options.cancelBookings() || options.cancelClasses());
        for(String ring:new LinkedHashSet<>(request.ringIds())) validate(ring,request.from(),request.to(),RingBlockKind.BLOCK,RingBlockReason.ACTIVITY,true);
        for(String ring:new TreeSet<>(request.ringIds())) lockSlots(ring,request.from(),request.to());
        resolve(conflictsFor(request),options,request.createdByAccountId());
        var existing=blocks.forActivity(request.activityId());
        for(var block:existing) if(!request.ringIds().contains(block.ringId())) cancelBlock(block);
        for(String ring:new LinkedHashSet<>(request.ringIds())) {
            var before=existing.stream().filter(b -> b.ringId().equals(ring)).findFirst().orElse(null);
            if(before==null) insert(ring,request.from(),request.to(),RingBlockKind.BLOCK,RingBlockReason.ACTIVITY,null,request.activityId(),request.createdByAccountId());
            else if(!before.from().equals(request.from()) || !before.to().equals(request.to())) {
                var after=new RingBlock(before.id(),before.clubId(),ring,request.from(),request.to(),before.kind(),before.reason(),before.note(),before.activityId(),before.state(),null,null,
                        before.version()+1,before.createdAt(),before.createdByAccountId(),clock.instant(),events.actor());
                blocks.update(after,before.version()); changed(before,after);
            }
        }
    }
    public java.util.List<String> activityBlockIds(String activityId) { return blocks.forActivity(activityId).stream().map(RingBlock::id).toList(); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelForActivity(String activityId) { context.lockReferences(); blocks.forActivity(activityId).forEach(this::cancelBlock); }
}
