package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read the source in its own scope; write every selected destination part in one transaction. */
@Service
public class PlatformCatalogClone {
    public record Source(Club club,Map<String,Object> catalogs,List<Map<String,Object>> templates,java.time.LocalDate today) { }
    public record Result(String id,Map<String,Integer> copied,List<Warning> warnings) { }
    /**
     * S17 R-17-08 (E118 #3, E10-T04): one localized-text adjustment of a part. `LOCALE_OMITTED`: the source's `from` locale is
     * not one of the destination's, so its texts were left out; `DEFAULT_LOCALE_COPIED`: a text had no destination default
     * locale, so the source's `from` text was copied into it.
     */
    public record Warning(String code,String part,String from) { }
    private final ClubRepository clubs;private final PlatformClubChanges changes;private final ClubCatalogProvisioner catalogs;
    private final ClubTemplateProvisioner templates;private final ObjectMapper mapper;private final ClubClock clock;
    public PlatformCatalogClone(ClubRepository clubs,PlatformClubChanges changes,ClubCatalogProvisioner catalogs,ClubTemplateProvisioner templates,ObjectMapper mapper,ClubClock clock) {
        this.clubs=clubs;this.changes=changes;this.catalogs=catalogs;this.templates=templates;this.mapper=mapper;this.clock=clock;
    }
    /**
     * S17 §3 `catalogs.fromTemplate`: a template club named by its slug (`club-template-default`), else by its id. The resolved
     * id scopes the read; `POST …/catalog-clone {fromClubId}` keeps id semantics ({@link #source}).
     */
    public Source template(String reference) {
        var club=clubs.findBySlug(reference).or(()->clubs.findById(reference)).orElseThrow(()->new ApiException(ErrorCode.TEMPLATE_CLUB_NOT_FOUND));
        return source(club.id());
    }
    public Source source(String id) {
        var club=clubs.findById(id).orElseThrow(()->new ApiException(ErrorCode.TEMPLATE_CLUB_NOT_FOUND));
        try(var scope=TenantContext.open(id)) { return new Source(club,catalogs.cloneSource(),templates.cloneSource(),clock.today(id)); }
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="T(java.util.Map).of()",reason="'Catalog clone'",details="#result.copied")
    public Result apply(Source source,Set<String> parts) {
        var target=changes.club();var current=catalogs.cloneSource();var copied=new LinkedHashMap<String,Integer>();var warnings=new ArrayList<Warning>();
        if(parts.contains("PLANS")&&!target.currency().equals(source.club().currency())) throw new ApiException(ErrorCode.CURRENCY_MISMATCH);
        var input=mapper.createObjectNode();
        for(String part:new TreeSet<>(parts)) {
            String section=section(part);
            boolean empty=part.equals("MESSAGE_TEMPLATES")?templates.cloneSource().isEmpty():((List<?>)current.get(section)).isEmpty();
            if(part.equals("PLANS")) empty&=((List<?>)current.get("prices")).isEmpty();
            if(!empty) throw new ApiException(ErrorCode.CATALOG_NOT_EMPTY,Map.of("part",part));
            if(part.equals("MESSAGE_TEMPLATES")) continue;
            var data=mapper.valueToTree(source.catalogs().get(section));
            trim(data,target,source.club().defaultLocale(),warnings,part);
            if(part.equals("RINGS")) for(var row:data) ((com.fasterxml.jackson.databind.node.ObjectNode)row).remove(List.of("geometry","activeSetupId","calibration","geometryVersion"));
            input.set(section,data);copied.put(part,data.size());
            if(part.equals("PLANS")) {
                var prices=input.putArray("prices");String today=source.today().toString();
                for(var price:mapper.valueToTree(source.catalogs().get("prices")))
                    if(price.path("validFrom").asText().compareTo(today)<=0&&(!price.hasNonNull("validTo")||price.path("validTo").asText().compareTo(today)>=0)) prices.add(price);
            }
        }
        // Optimistic write serializes concurrent clones. Catalog services additionally hold their ordinary locks.
        changes.write(target,Map.of(),"ClubUpdated",Map.of("catalogClone",new TreeSet<>(parts)));
        catalogs.provision(catalogs.plan(input));
        if(parts.contains("MESSAGE_TEMPLATES")) {
            var data=mapper.valueToTree(source.templates());trim(data,target,source.club().defaultLocale(),warnings,"MESSAGE_TEMPLATES");
            templates.cloneInto(mapper.convertValue(data,new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){}),target.defaultLocale());
            copied.put("MESSAGE_TEMPLATES",data.size());
        }
        return new Result(target.id(),copied,List.copyOf(new LinkedHashSet<>(warnings)));
    }
    private String section(String part) { return switch(part) {case "LEVELS"->"levels";case "RINGS"->"rings";case "PLANS"->"plans";case "FAQ"->"faq";case "MESSAGE_TEMPLATES"->"messageTemplates";default->throw new ApiException(ErrorCode.VALIDATION_ERROR);}; }
    private void trim(JsonNode node,Club target,String sourceLocale,List<Warning> warnings,String part) {
        if(node.isArray()) { node.forEach(v->trim(v,target,sourceLocale,warnings,part));return; }
        if(!node.isObject()) return;
        var obj=(com.fasterxml.jackson.databind.node.ObjectNode)node;var names=new ArrayList<String>();obj.fieldNames().forEachRemaining(names::add);
        if(!names.isEmpty()&&Set.of("ca","es","en").containsAll(names)) {
            String fallback=obj.path(sourceLocale).asText(obj.elements().next().asText());
            for(String name:names) if(!target.locales().contains(name)) {obj.remove(name);warnings.add(new Warning("LOCALE_OMITTED",part,name));}
            if(!obj.has(target.defaultLocale())) {obj.put(target.defaultLocale(),fallback);warnings.add(new Warning("DEFAULT_LOCALE_COPIED",part,sourceLocale));}
        } else names.forEach(name->trim(obj.get(name),target,sourceLocale,warnings,part));
    }
}
