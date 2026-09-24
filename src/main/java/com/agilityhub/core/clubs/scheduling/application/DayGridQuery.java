package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingOccupancyPort;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.LocaleContext;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DayGridQuery {
    private final ClassSessionRepository classes; private final RingBlockRepository blocks; private final SessionProjection projection;
    private final PlanningCatalogAccess catalogs; private final TrainingOccupancyPort training;
    public DayGridQuery(ClassSessionRepository classes,RingBlockRepository blocks,SessionProjection projection,PlanningCatalogAccess catalogs,TrainingOccupancyPort training) {
        this.classes=classes; this.blocks=blocks; this.projection=projection; this.catalogs=catalogs; this.training=training;
    }
    public Map<String,Object> get(LocalDate date,boolean staff) {
        var zone=projection.zone(); var from=date.atStartOfDay(zone).toInstant(); var to=date.plusDays(1).atStartOfDay(zone).toInstant();
        var selected=classes.between(from,to).stream().filter(c -> c.state()!=ClassState.DRAFT && (staff || c.state()!=ClassState.CANCELLED))
                .sorted(Comparator.comparing(ClassSession::startsAt).thenComparing(ClassSession::id)).toList();
        var rows=new TreeMap<String,List<Map<String,Object>>>();
        for(var c:selected) {
            var cell=new LinkedHashMap<String,Object>(); cell.put("ringId",c.ringId()); cell.put("kind","CLASS"); cell.put("endTime",c.endTime()); cell.put("classId",c.id());
            cell.put("description",projection.description(c,LocaleContext.current())); cell.put("instructorName",projection.instructorName(c,staff)); cell.put("state",c.state());
            var risk=projection.risk(c); cell.put("atRisk",risk.atRisk()); cell.put("riskText",risk.text());
            if(staff) { var counts=new LinkedHashMap<String,Object>(); counts.put("booked",c.counters().booked()); counts.put("capacity",c.capacity()); if(projection.enabled(Module.WAITLIST)) counts.put("waiting",c.counters().waiting()); cell.put("occupancy",counts); }
            rows.computeIfAbsent(c.startTime(),k -> new ArrayList<>()).add(cell);
        }
        // The port's block intervals repeat these blocks; the MEMBER view carries no block id (R-09-12), so match by ring and time too.
        var ringBlocks=blocks.between(from,to); var ids=new HashSet<String>(); ringBlocks.forEach(b -> { ids.add(b.id()); ids.add(b.ringId()+"|"+b.from()+"|"+b.to()); });
        for(var b:ringBlocks) {
            if(!projection.visible(b)) continue;
            var block=projection.block(b,!staff); var cell=new LinkedHashMap<String,Object>(); cell.put("ringId",b.ringId()); cell.put("endTime",b.to().atZone(zone).toLocalTime().toString());
            if(b.reason()==RingBlockReason.ACTIVITY) { cell.put("kind","ACTIVITY"); cell.put("activityId",b.activityId()); cell.put("title",block.get("activityTitle")); }
            else { cell.put("kind",staff?"BLOCK":"OCCUPIED"); cell.put("reason",b.reason()); if(staff) { cell.put("blockId",b.id()); cell.put("note",b.note()); cell.put("createdByName",block.get("createdByName")); } }
            rows.computeIfAbsent(b.from().isBefore(from)?"00:00":b.from().atZone(zone).toLocalTime().toString(),k -> new ArrayList<>()).add(cell);
        }
        if(projection.enabled(Module.FREE_TRAINING)) for(var t:training.occupancy(from,to,null,staff?"INSTRUCTOR":"MEMBER")) {
            if(t.type()==TrainingOccupancyPort.Type.RING_BLOCK && ids.contains(t.id()==null ? t.ringId()+"|"+t.from()+"|"+t.to() : t.id())) continue;
            if("ACTIVITY".equals(t.reason())) continue; // Activity blocks are owned and projected above.
            var cell=new LinkedHashMap<String,Object>(); cell.put("ringId",t.ringId()); cell.put("endTime",t.to().atZone(zone).toLocalTime().toString());
            cell.put("kind",staff?(t.type()==TrainingOccupancyPort.Type.TRAINING?"TRAINING":"BLOCK"):"OCCUPIED"); cell.put("reason",t.type()==TrainingOccupancyPort.Type.TRAINING?"TRAINING":t.reason());
            if(staff) {
                if(t.type()==TrainingOccupancyPort.Type.TRAINING) { cell.put("who",List.of(Objects.toString(t.memberName(),"")+" + "+Objects.toString(t.dogName(),""))); cell.put("trainingBookingIds",List.of(t.id())); }
                else { cell.put("blockId",t.id()); cell.put("note",t.note()); }
            }
            rows.computeIfAbsent(t.from().isBefore(from)?"00:00":t.from().atZone(zone).toLocalTime().toString(),k -> new ArrayList<>()).add(cell);
        }
        var columns=new ArrayList<Map<String,Object>>();
        for(var ring:catalogs.rings()) if(ring.active()) {
            var column=new LinkedHashMap<String,Object>(); column.put("ringId",ring.id()); column.put("shortName",ring.shortName()); column.put("name",ring.name()); column.put("color",ring.color());
            if(projection.enabled(Module.COURSES)) column.put("activeSetupId",ring.activeSetupId()); columns.add(column);
        }
        if(selected.stream().anyMatch(c -> c.ringId()==null)) {
            String label=projection.noRing();
            var column=new LinkedHashMap<String,Object>(); column.put("ringId",null); column.put("shortName",label); column.put("name",label); column.put("color",""); columns.add(column);
        }
        return Map.of("date",date,"dayOfWeek",date.getDayOfWeek(),"timeZone",zone.getId(),"view",staff?"INSTRUCTOR":"MEMBER","columns",columns,
                "rows",rows.entrySet().stream().map(row -> Map.of("time",row.getKey(),"cells",row.getValue())).toList());
    }
}
