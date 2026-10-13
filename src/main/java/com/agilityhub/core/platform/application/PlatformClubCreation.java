package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.domain.ConsoleThemeRules;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformClubCreation {
    public record Created(String id,Map<String,Object> definition) { }
    private final PlatformDefinitionSchema schema;private final ClubDefinitionCodec codec;private final ClubDefinitionMapper definitions;
    private final ClubRepository clubs;private final PlatformClubChanges changes;private final ParameterSettingsService parameters;
    private final ClubCatalogProvisioner catalogs;private final ClubTemplateProvisioner templates;private final ClubPageProvisioner pages;
    private final ConsoleAdministratorAccess admins;private final PlatformProviders providers;private final ObjectMapper mapper;private final Clock clock;
    public PlatformClubCreation(PlatformDefinitionSchema schema,ClubDefinitionCodec codec,ClubDefinitionMapper definitions,ClubRepository clubs,
            PlatformClubChanges changes,ParameterSettingsService parameters,ClubCatalogProvisioner catalogs,ClubTemplateProvisioner templates,ClubPageProvisioner pages,
            ConsoleAdministratorAccess admins,PlatformProviders providers,ObjectMapper mapper,Clock clock) {
        this.schema=schema;this.codec=codec;this.definitions=definitions;this.clubs=clubs;this.changes=changes;this.parameters=parameters;
        this.catalogs=catalogs;this.templates=templates;this.pages=pages;this.admins=admins;this.providers=providers;this.mapper=mapper;this.clock=clock;
    }
    @org.springframework.beans.factory.annotation.Autowired private PlatformCatalogClone clones;
    @org.springframework.beans.factory.annotation.Autowired private CountryProfileRegistry countries;
    public ObjectNode prepare(Map<String,Object> input) {
        schema.validate(input);ObjectNode definition=mapper.valueToTree(input);
        if("COMPLET".equals(definition.path("preset").asText())) {
            definition.remove("preset");definition.set("modules",mapper.valueToTree(allModules()));
        }
        // Validate WP-A's semantic rules using a credential-free copy. Never resolve an env ref into an exported definition.
        ObjectNode structural=definition.deepCopy();structural.remove("paymentProviders");
        if(structural.path("catalogs").isObject()) ((ObjectNode)structural.path("catalogs")).remove("fromTemplate");
        var normalized=codec.validate(structural);
        if(definition.has("paymentProviders")) normalized.set("paymentProviders",definition.get("paymentProviders"));
        if(definition.path("catalogs").has("fromTemplate")) normalized.withObject("catalogs").set("fromTemplate",definition.path("catalogs").get("fromTemplate"));
        return normalized;
    }
    public static Set<Module> allModules() { var modules=EnumSet.allOf(Module.class);modules.removeAll(Set.of(Module.STATS,Module.SOCIAL_LEAGUE));return modules; }
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="T(java.util.Map).of()",reason="'source: CONSOLE'")
    public Created create(ObjectNode definition,PlatformCatalogClone.Source source) {
        String id=TenantContext.require(),slug=definition.path("club").path("slug").asText();
        if(clubs.findBySlug(slug).isPresent()) throw new ApiException(ErrorCode.SLUG_TAKEN);
        for(var domain:definition.path("domains")) if(clubs.findByAnyHost(domain.path("host").asText()).isPresent()) throw new ApiException(ErrorCode.HOST_ALREADY_USED);
        var raw=definition.deepCopy();raw.withObject("club").put("status","ONBOARDING");
        // Missing optional tokens inherit the supplied foreground/background, not a club-specific palette.
        var colors=raw.withObject("theme").withObject("colors");
        Map<String,String> fallback=Map.of("onPrimary",colors.path("background").asText(),"surfaceAlt",colors.path("surface").asText(),
                "textMuted",colors.path("text").asText(),"border",colors.path("text").asText(),"success",colors.path("primary").asText(),
                "warning",colors.path("primary").asText(),"danger",colors.path("primary").asText(),"info",colors.path("primary").asText());
        fallback.forEach((k,v)->{if(!colors.has(k)) colors.put(k,v);});
        var club=definitions.merge(raw,null,id,clock.instant(),false);ConsoleThemeRules.validate(club.theme());
        if(club.taxId()!=null&&!countries.get(club.countryProfile()).validateTaxId(club.taxId())) throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field","taxId"));
        try { clubs.save(club); } catch(org.springframework.dao.DuplicateKeyException duplicate) { throw new ApiException(duplicate.getMessage().contains("club_host")?ErrorCode.HOST_ALREADY_USED:ErrorCode.SLUG_TAKEN); }
        var providerFields=definition.path("paymentProviders");
        if(providerFields.isObject()) for(var entry:providerFields.properties()) {
            var input=new LinkedHashMap<String,Object>();entry.getValue().properties().forEach(f->{
                Object value=f.getValue().isObject()&&f.getValue().has("env")?environment(f.getValue().path("env").asText()):mapper.convertValue(f.getValue(),Object.class);
                input.put(f.getKey(),value);
            });
            if(input.size()>1) { input.put("version",changes.club().version());providers.update(entry.getKey(),input); }
        }
        for(var entry:definition.path("parameters").properties()) parameters.updatePlatform(entry.getKey(),mapper.convertValue(entry.getValue(),Object.class),null,0L,"source: CONSOLE");
        catalogs.provision(catalogs.plan(definition.path("catalogs")));
        templates.provision(templates.plan(definition.path("messageTemplates")),club.locales(),club.defaultLocale());
        for(var entry:definition.path("pages")) pages.provision(mapper.convertValue(entry,ClubPageProvisioner.Page.class),club.defaultLocale(),club.locales());
        for(var entry:definition.path("admins")) admins.invite(entry.path("email").asText(),entry.path("name").asText());
        if(source!=null) clones.apply(source,Set.of("LEVELS","RINGS","PLANS","FAQ","MESSAGE_TEMPLATES"));
        changes.publish("ClubCreated",id,Map.of("slug",slug,"source","CONSOLE"));
        return new Created(id,Map.of("slug",slug,"source","CONSOLE"));
    }
    private String environment(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank()) throw new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID);
        return value;
    }
}
