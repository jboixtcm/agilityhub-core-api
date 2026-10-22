package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.persistence.Club;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.platform.api.PlatformClubContracts.*;

@Component
public class PlatformClubMapper {
    private final PlatformConsoleQueries queries;private final PlatformProviders providers;private final ObjectMapper mapper;private final PlatformDomains domains;
    public PlatformClubMapper(PlatformConsoleQueries queries,PlatformProviders providers,ObjectMapper mapper,PlatformDomains domains) {this.queries=queries;this.providers=providers;this.mapper=mapper;this.domains=domains;}
    public OnboardingChecklist checklist(Club c) {return mapper.convertValue(queries.checklist(c),OnboardingChecklist.class);}
    public PlatformClubUsage usage(Club c,String month) {return mapper.convertValue(queries.usage(c,month),PlatformClubUsage.class);}
    /** The read projection is separate from the value-free provider audit snapshot. */
    public PlatformProviderSummary provider(String name,Object entry) {
        var summary=providers.summary(name,entry);
        Map<?,?> config=entry instanceof Map<?,?> value?value:Map.of();
        boolean sepa="SEPA_XML".equals(name);
        String account="STRIPE".equals(name)?PlatformProviders.text(config,"accountId"):null;
        Map<String,String> instructions="MANUAL".equals(name)?mapper.convertValue(config.get("instructions"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}):null;
        return new PlatformProviderSummary(summary.enabled(),summary.configured(),summary.masked(),summary.last4(),
                summary.mode()==null?null:StripeMode.valueOf(summary.mode().toUpperCase(Locale.ROOT)),summary.lastTestAt(),
                sepa?PlatformProviders.text(config,"creditorName"):null,sepa?PlatformProviders.text(config,"creditorId"):null,
                sepa?PlatformProviders.text(config,"suffix"):null,sepa?summary.masked():null,instructions,
                account==null?null:"****"+account.substring(Math.max(0,account.length()-4)));
    }
    public PlatformClub club(Club c) {
        var configured=new LinkedHashMap<Provider,PlatformProviderSummary>();c.paymentProviders().forEach((k,v)->configured.put(Provider.valueOf(k),provider(k,v)));
        return new PlatformClub(c.id(),c.slug(),c.name(),c.legalName(),c.taxId(),mapper.convertValue(c.address(),SettingsContracts.ClubAddress.class),
                c.displayCity(),c.contactEmail(),c.contactPhone(),c.websiteUrl(),c.locales(),c.defaultLocale(),c.timeZone(),c.currency(),c.countryProfile(),
                c.domains().stream().map(this::domain).toList(),c.theme(),c.modules(),configured,
                mapper.convertValue(c.legal(),PlatformClubLegal.class),ClubStatus.valueOf(c.status().name()),c.template(),c.notes(),checklist(c),usage(c,null),c.version(),c.createdAt(),c.updatedAt());
    }
    /** S17 R-17-03: the TXT challenge while the domain has one (E10-T04); a verified or legacy domain has none. */
    public PlatformDomain domain(Club.Domain d) {
        var challenge=d.verificationToken()==null?null:new DomainVerificationChallenge(
                com.agilityhub.core.platform.domain.DomainChallenge.recordName(d.host()),com.agilityhub.core.platform.domain.DomainChallenge.recordValue(d.verificationToken()),domains.cnameTarget(d.app()));
        return new PlatformDomain(d.host(),d.app().equals("clubs-admin")?DomainApp.CLUBS_ADMIN:DomainApp.CLUBS,
                DomainStatus.valueOf(d.status().name()),d.verifiedAt(),d.primary(),challenge,d.checkedAt(),d.failures());
    }
    public Map<String,Object> row(Club c,List<String> fields) {
        var value=new LinkedHashMap<String,Object>();value.put("id",c.id());value.put("name",c.name());value.put("slug",c.slug());value.put("status",c.status());
        value.put("countryProfile",c.countryProfile());value.put("timeZone",c.timeZone());value.put("modules",c.modules());value.put("template",c.template());
        value.put("lastAdminAccessAt",queries.lastAdminAccess(c));value.put("usage",usage(c,null));value.put("onboardingChecklist",checklist(c));
        if(!fields.isEmpty()) {var selected=new HashSet<>(fields);selected.add("id");value.keySet().retainAll(selected);}return value;
    }
}
