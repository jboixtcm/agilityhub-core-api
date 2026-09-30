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
    private final com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess catalogs;
    public SchedulingLists(SessionProjection projection,ClassSessionService classes,RingBlockService blocks,com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess catalogs) {
        this.projection=projection; this.classes=classes; this.blocks=blocks; this.catalogs=catalogs;
    }
    public Set<String> keys() { return Set.of("class-sessions","ring-blocks"); }
    public ListDataset dataset(String key) {
        boolean session=key.equals("class-sessions"); var fields=new ArrayList<>(session ? List.of("id","weekId","date","startTime","endTime","startsAt","endsAt","ringId","levelIds","instructorIds","capacity","capacityMode","description","displayDescription","state","counters","atRisk","riskExempt","cancellation","origin","version","inconsistencyIds")
                : List.of("id","ringId","ringName","ringColor","from","to","date","fromLocal","toLocal","kind","reason","activityId","activityTitle","state","version"));
        if(session && projection.enabled(Module.COURSES)) fields.add("placementId");
        if(session ? RingBlockService.role("ADMIN") : !projection.member()) fields.add(session?"notes":"note");
        if(!session && !projection.member()) fields.add("createdByName");
        var filters=new HashMap<String,ListDefinition.Field>(); filters.put("id",new ListDefinition.Field("_id",ListDefinition.Type.TEXT));
        for(String f:session?List.of("date","state","ringId","instructorId","levelId","weekId"):List.of("ringId","kind","reason","state","from","to"))
            filters.put(f,new ListDefinition.Field(f.equals("instructorId")?"instructorIds":f.equals("levelId")?"levelIds":f,f.equals("date")?ListDefinition.Type.DATE:Set.of("from","to").contains(f)?ListDefinition.Type.INSTANT:ListDefinition.Type.TEXT));
        var sorts=session?Map.of("startsAt","startsAt","date","date"):Map.of("from","from");
        // CONVENCIONS_API §4, S09 §2 (ruling E75): the blocks search the ring's name (a deactivated ring's included) and the note, which
        // only the staff read: a member's `q` never matches a note. The class sessions have no search, so a non-blank `q` is INVALID_FILTER.
        List<String> search=session?List.of():projection.member()?List.of("ringSearchName"):List.of("ringSearchName","note");
        var definition=new ListDefinition(key,filters,sorts,search,fields,fields,List.of(session?"startsAt,asc":"from,asc"),Set.copyOf(fields));
        var output=new LinkedHashMap<String,Object>(); fields.forEach(f -> output.put(f,1)); output.put("id","$_id");
        var stages=new ArrayList<Document>(); if(!session && !projection.enabled(Module.ACTIVITIES)) stages.add(new Document("$match",new Document("reason",new Document("$ne","ACTIVITY"))));
        if(!session) {
            stages.add(new Document("$lookup",new Document("from","rings").append("let",new Document("ref","$ringId").append("club","$clubId"))
                    .append("pipeline",List.of(new Document("$match",new Document("$expr",new Document("$and",List.of(
                            new Document("$eq",List.of("$clubId","$$club")),new Document("$eq",List.of("$_id","$$ref")))))),
                            new Document("$project",new Document("name",1)))).append("as","ringRows")));
            stages.add(new Document("$set",new Document("ringSearchName",new Document("$ifNull",List.of(new Document("$arrayElemAt",List.of("$ringRows.name",0)),"")))));
        }
        return new ListDataset(definition,session?"class_sessions":"ring_blocks",stages,output,Set.of(),session?(field,value)->Objects.toString(value,""):this::blockLabel);
    }
    /** `GET /ring-blocks/filter-values` labels (E5-T29): the ring's name, a deactivated ring's included; any other field its value. */
    private String blockLabel(String field,Object value) {
        String id=Objects.toString(value,"");
        if(!field.equals("ringId")) return id;
        return catalogs.rings().stream().filter(r -> r.id().equals(id)).map(r -> r.name()).findFirst().orElse(id);
    }
    public ListPage<Map<String,Object>> list(ListEngine engine,String key,MultiValueMap<String,String> params) {
        var page=engine.list(key,params); var requested=params.getFirst("fields");
        // The keys as the engine validated them (trimmed), so `fields=id, state` keeps `state` (E5-T22).
        Set<String> fields=requested==null?null:Set.copyOf(ListQuery.csv(requested));
        // E5-T29 (the register /entrenaments shows history): each block's ring name and colour, a deactivated ring's included.
        var rings=new HashMap<String,com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess.RingView>();
        if(key.equals("ring-blocks")) catalogs.rings().forEach(r -> rings.put(r.id(),r));
        var items=page.items().stream().map(row -> {
            Map<String,Object> value;
            if(key.equals("class-sessions")) value=projection.session(classes.require(row.get("id").toString()),false,List.of());
            else value=block(projection.block(blocks.require(row.get("id").toString()),projection.member()),rings);
            if(fields!=null) value.keySet().removeIf(f -> !f.equals("id") && !fields.contains(f)); return value;
        }).toList();
        return new ListPage<>(items,page.page(),page.size(),page.totalItems(),page.totalPages(),page.appliedFilters());
    }
    /** A `RingBlockListItem` row: the block's projection with `ringName` and `ringColor` after `ringId` (null for a ring that is gone). */
    private static Map<String,Object> block(Map<String,Object> projected,Map<String,com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess.RingView> rings) {
        var ring=rings.get(Objects.toString(projected.get("ringId"),"")); var out=new LinkedHashMap<String,Object>();
        projected.forEach((name,value) -> {
            out.put(name,value);
            if(name.equals("ringId")) { out.put("ringName",ring==null?null:ring.name()); out.put("ringColor",ring==null?null:ring.color()); }
        });
        return out;
    }
    /** CONVENCIONS_API §4: the values of `field` (an `x-filterable` field of `GET /ring-blocks`) with their counts over the whole filtered set. */
    public com.agilityhub.core.shared.application.contract.ApiContracts.FilterValues blockFilterValues(ListEngine engine,String field,MultiValueMap<String,String> params) {
        return engine.facets("ring-blocks",field,params);
    }
}
