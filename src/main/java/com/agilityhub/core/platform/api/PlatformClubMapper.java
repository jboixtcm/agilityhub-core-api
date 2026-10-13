package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.persistence.Club;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.platform.api.PlatformClubContracts.*;

@Component
public class PlatformClubMapper {
    private final PlatformConsoleQueries queries;private final PlatformProviders providers;private final ObjectMapper mapper;
    public PlatformClubMapper(PlatformConsoleQueries queries,PlatformProviders providers,ObjectMapper mapper) {this.queries=queries;this.providers=providers;this.mapper=mapper;}
    public OnboardingChecklist checklist(Club c) {return mapper.convertValue(queries.checklist(c),OnboardingChecklist.class);}
    public PlatformClubUsage usage(Club c,String month) {return mapper.convertValue(queries.usage(c,month),PlatformClubUsage.class);}
    public PlatformProviderSummary provider(PlatformProviders.Summary s) {return mapper.convertValue(s,PlatformProviderSummary.class);}
    public PlatformClub club(Club c) {
        var configured=new LinkedHashMap<Provider,PlatformProviderSummary>();providers.summaries(c).forEach((k,v)->configured.put(Provider.valueOf(k),provider(v)));
        return new PlatformClub(c.id(),c.slug(),c.name(),c.legalName(),c.taxId(),mapper.convertValue(c.address(),SettingsContracts.ClubAddress.class),
                c.displayCity(),c.contactEmail(),c.contactPhone(),c.websiteUrl(),c.locales(),c.defaultLocale(),c.timeZone(),c.currency(),c.countryProfile(),
                c.domains().stream().map(d->new PlatformDomain(d.host(),d.app().equals("clubs-admin")?DomainApp.CLUBS_ADMIN:DomainApp.CLUBS,
                        DomainStatus.valueOf(d.status().name()),d.verifiedAt(),d.primary())).toList(),c.theme(),c.modules(),configured,
                mapper.convertValue(c.legal(),PlatformClubLegal.class),ClubStatus.valueOf(c.status().name()),c.template(),c.notes(),checklist(c),usage(c,null),c.version(),c.createdAt(),c.updatedAt());
    }
    public Map<String,Object> row(Club c,List<String> fields) {
        var value=new LinkedHashMap<String,Object>();value.put("id",c.id());value.put("name",c.name());value.put("slug",c.slug());value.put("status",c.status());
        value.put("countryProfile",c.countryProfile());value.put("timeZone",c.timeZone());value.put("modules",c.modules());value.put("template",c.template());
        value.put("lastAdminAccessAt",queries.lastAdminAccess(c));value.put("usage",usage(c,null));value.put("onboardingChecklist",checklist(c));
        if(!fields.isEmpty()) {var selected=new HashSet<>(fields);selected.add("id");value.keySet().retainAll(selected);}return value;
    }
}
