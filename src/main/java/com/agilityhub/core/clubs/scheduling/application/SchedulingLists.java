package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.platform.application.Module;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
public class SchedulingLists implements ListProvider {
    private final SessionProjection projection; private final ClassSessionService classes; private final RingBlockService blocks;
    public SchedulingLists(SessionProjection projection,ClassSessionService classes,RingBlockService blocks) { this.projection=projection; this.classes=classes; this.blocks=blocks; }
    public Set<String> keys() { return Set.of("class-sessions","ring-blocks"); }
    public ListDataset dataset(String key) {
        boolean session=key.equals("class-sessions"); var fields=new ArrayList<>(session ? List.of("id","weekId","date","startTime","endTime","startsAt","endsAt","ringId","levelIds","instructorIds","capacity","capacityMode","description","displayDescription","state","counters","atRisk","riskExempt","cancellation","origin","version","inconsistencyIds")
                : List.of("id","ringId","from","to","date","fromLocal","toLocal","kind","reason","activityId","activityTitle","state","version"));
        if(session && projection.enabled(Module.COURSES)) fields.add("placementId");
        if(session ? RingBlockService.role("ADMIN") : !projection.member()) fields.add(session?"notes":"note");
        if(!session && !projection.member()) fields.add("createdByName");
        var filters=new HashMap<String,ListDefinition.Field>(); filters.put("id",new ListDefinition.Field("_id",ListDefinition.Type.TEXT));
        for(String f:session?List.of("date","state","ringId","instructorId","levelId","weekId"):List.of("ringId","kind","reason","state","from","to"))
            filters.put(f,new ListDefinition.Field(f.equals("instructorId")?"instructorIds":f.equals("levelId")?"levelIds":f,f.equals("date")?ListDefinition.Type.DATE:Set.of("from","to").contains(f)?ListDefinition.Type.INSTANT:ListDefinition.Type.TEXT));
        var sorts=session?Map.of("startsAt","startsAt","date","date"):Map.of("from","from");
        var definition=new ListDefinition(key,filters,sorts,List.of(),fields,fields,List.of(session?"startsAt,asc":"from,asc"),Set.copyOf(fields));
        var output=new LinkedHashMap<String,Object>(); fields.forEach(f -> output.put(f,1)); output.put("id","$_id");
        var stages=new ArrayList<Document>(); if(!session && !projection.enabled(Module.ACTIVITIES)) stages.add(new Document("$match",new Document("reason",new Document("$ne","ACTIVITY"))));
        return new ListDataset(definition,session?"class_sessions":"ring_blocks",stages,output,Set.of(),(field,value)->Objects.toString(value,""));
    }
    public ListPage<Map<String,Object>> list(ListEngine engine,String key,MultiValueMap<String,String> params) {
        var page=engine.list(key,params); var requested=params.getFirst("fields");
        // The keys as the engine validated them (trimmed), so `fields=id, state` keeps `state` (E5-T22).
        Set<String> fields=requested==null?null:Set.copyOf(ListQuery.csv(requested));
        var items=page.items().stream().map(row -> {
            var value=key.equals("class-sessions")?projection.session(classes.require(row.get("id").toString()),false,List.of()):projection.block(blocks.require(row.get("id").toString()),projection.member());
            if(fields!=null) value.keySet().removeIf(f -> !f.equals("id") && !fields.contains(f)); return value;
        }).toList();
        return new ListPage<>(items,page.page(),page.size(),page.totalItems(),page.totalPages(),page.appliedFilters());
    }
}
