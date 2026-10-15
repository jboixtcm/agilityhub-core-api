package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.domain.*;
import com.agilityhub.core.platform.domain.events.ConsoleEvent;
import com.agilityhub.core.platform.domain.events.ClubPaymentProviderChanged;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.domain.audit.AuditField;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Console mutations hold the club version for their entire transaction. No tenant is inferred from input data. */
@Service
public class PlatformClubChanges {
    public record Change(String id, Club club, @AuditField Map<String,Object> settings) { }
    public record Administration(String id, String defaultLocale, boolean onboarding, long version) { }
    static final List<String> REQUIRED=List.of("name","locales","defaultLocale","timeZone","currency","countryProfile","legal");
    @org.springframework.beans.factory.annotation.Autowired private PlatformThemeFiles themeFiles;
    private final ClubRepository clubs;
    private final ClubConfigService configs;
    private final ModuleDependencyValidator modules;
    private final ClubScheduleAccess schedule;
    private final CountryProfileRegistry countries;
    private final EventPublisher events;
    private final AuditActorProvider actors;
    private final ObjectMapper mapper;
    private final Clock clock;
    public PlatformClubChanges(ClubRepository clubs,ClubConfigService configs,ModuleDependencyValidator modules,
            ClubScheduleAccess schedule,CountryProfileRegistry countries,EventPublisher events,AuditActorProvider actors,ObjectMapper mapper,Clock clock) {
        this.clubs=clubs;this.configs=configs;this.modules=modules;this.schedule=schedule;this.countries=countries;
        this.events=events;this.actors=actors;this.mapper=mapper;this.clock=clock;
    }
    public Club club() { return clubs.findById(TenantContext.require()).orElseThrow(()->new ApiException(ErrorCode.CLUB_NOT_FOUND)); }
    public Administration administration() {
        var club=club();
        return new Administration(club.id(),club.defaultLocale(),club.status()==Club.Status.ONBOARDING,club.version());
    }
    public void administratorChange(Administration before,Map<String,Object> diff) {
        var current=club();requireVersion(current,before.version());
        write(current,Map.of(),"ClubUpdated",diff);
    }
    public Change snapshot() {
        var c=club();var fields=new LinkedHashMap<String,Object>();
        for(String key:List.of("name","legalName","taxId","address","displayCity","contactEmail","contactPhone","websiteUrl",
                "locales","defaultLocale","timeZone","currency","countryProfile","legal","template","notes","theme","modules","status"))
            fields.put(key,mapper.convertValue(c,Map.class).get(key));
        return new Change(c.id(),c,fields);
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="snapshot()")
    public Change patch(Map<String,Object> changes) {
        var old=club();requireVersion(old,changes.get("version"));
        var allowed=new HashSet<>(snapshot().settings().keySet());allowed.removeAll(Set.of("status","modules","theme"));allowed.add("version");
        if(!allowed.containsAll(changes.keySet())) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        // Omitted means unchanged; an explicit null would erase a value every configuration read depends on (`legal` above all).
        for(String key:REQUIRED) if(changes.containsKey(key)&&changes.get(key)==null) throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field",key));
        if(changes.containsKey("timeZone")&&!Objects.equals(changes.get("timeZone"),old.timeZone())&&schedule.hasClasses())
            throw new ApiException(ErrorCode.TIMEZONE_CHANGE_BLOCKED);
        var tree=mapper.valueToTree(old);var node=(com.fasterxml.jackson.databind.node.ObjectNode)tree;
        changes.forEach((k,v)->{if(!k.equals("version")) node.set(k,mapper.valueToTree(v));});
        Club next;
        try { next=mapper.treeToValue(node,Club.class); }
        catch(com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException ex) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        if(changes.containsKey("taxId")) {
            String typed=Objects.toString(changes.get("taxId"),null);
            String tax=countries.get(next.countryProfile()).canonicalTaxId(CountryProfile.normalizeTaxId(typed));
            if(typed!=null&&!typed.isBlank()&&tax==null||tax!=null&&!Objects.equals(tax,old.taxId())&&!countries.get(next.countryProfile()).validateTaxId(tax))
                throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field","taxId"));
            node.put("taxId",tax);
        }
        if(changes.containsKey("legal")&&next.legal()!=null) localized(next.legal().imageConsentText(),next.locales(),next.defaultLocale());
        if(next.template()&&!next.domains().isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field","template"));
        var fields=new LinkedHashMap<String,Object>();
        changes.keySet().stream().filter(k->!k.equals("version")).forEach(k->fields.put(k,mapper.convertValue(node.get(k),Object.class)));
        write(old,fields,"ClubUpdated",safeDiff(old,fields));
        return snapshot();
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_MODULES_CHANGED,entityType="'Club'",entity="#result.id",before="snapshot()")
    public Change modules(long version,Set<Module> enabled) {
        var old=club();requireVersion(old,version);modules.validate(enabled);
        write(old,Map.of("modules",enabled),"ClubModulesChanged",Map.of("modules",Map.of("before",old.modules(),"after",enabled)));
        return snapshot();
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="snapshot()")
    public Change theme(long version,Theme theme) {
        var old=club();requireVersion(old,version);ConsoleThemeRules.validate(theme);
        write(old,Map.of("theme",theme),"ClubUpdated",Map.of("theme","changed"));return snapshot();
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="snapshot()")
    public Change themeFiles(long version,Theme theme,String logo,String dark,String mark) {
        var c=club();requireVersion(c,version);ConsoleThemeRules.validate(theme);
        var resolved=new Theme(themeFiles.claim(c,"logo",logo,c.theme().logoUrl()),themeFiles.claim(c,"dark",dark,c.theme().logoDarkUrl()),
                themeFiles.claim(c,"mark",mark,c.theme().markUrl()),theme.colors(),theme.fontFamily(),theme.radius(),theme.ringPalette(),theme.mode());
        return theme(version,resolved);
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_STATUS_CHANGED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="#reason")
    public Change status(Club.Status state,String reason) {
        var old=club();
        boolean allowed=old.status()==state||old.status()==Club.Status.ONBOARDING&&state==Club.Status.ACTIVE
                ||old.status()==Club.Status.ACTIVE&&state==Club.Status.SUSPENDED||old.status()==Club.Status.SUSPENDED&&state==Club.Status.ACTIVE;
        if(!allowed) throw new ApiException(ErrorCode.INVALID_STATE);
        if(state==Club.Status.SUSPENDED&&(reason==null||reason.isBlank())) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        if(old.status()!=state) {
            var diff=new LinkedHashMap<String,Object>();diff.put("before",old.status());diff.put("after",state);diff.put("reason",reason);
            write(old,Map.of("status",state),"ClubStatusChanged",diff);
        }
        return snapshot();
    }
    public Club write(Club old,Map<String,Object> fields,String type,Map<String,Object> diff) {
        Club next=clubs.updateVersioned(old.id(),old.version(),fields,clock.instant());
        publish(type,old.id(),diff);configs.invalidateAfterCommit(old.id());return next;
    }
    public void publish(String type,String id,Map<String,Object> diff) {
        if(type.equals("ClubPaymentProviderChanged")) {
            events.publish(new ClubPaymentProviderChanged(id,clock.instant(),
                    Map.of("clubId",id,"provider",diff.get("provider"),"configured",diff.get("configured")),
                    actors.current().accountId(),null,DomainEvent.Origin.BACKOFFICE));
            return;
        }
        events.publish(new ConsoleEvent(type,id,clock.instant(),Map.of("clubId",id,"diff",diff),actors.current().accountId()));
    }
    public static void requireVersion(Club club,Object version) {
        if(!(version instanceof Number n)||n.longValue()!=club.version()||n.doubleValue()!=n.longValue()) throw new ApiException(ErrorCode.STALE_VERSION);
    }
    public static void localized(Map<String,String> text,List<String> locales,String defaultLocale) {
        if(text==null||!locales.containsAll(text.keySet())||text.get(defaultLocale)==null||text.get(defaultLocale).isBlank())
            throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field","locales"));
    }
    private Map<String,Object> safeDiff(Club old,Map<String,Object> values) {
        var result=new LinkedHashMap<String,Object>();values.keySet().forEach(k->result.put(k,"changed"));return result;
    }
}
