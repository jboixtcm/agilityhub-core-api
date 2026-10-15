package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class PlatformConsoleQueries {
    public record Checklist(boolean dns,boolean sepa,boolean branding,boolean templates,boolean faq,boolean legal,boolean firstAdmin) { }
    public record Usage(String month,long membersActive,long smsSentMonth,long storageBytes) { }
    public record Page(List<Club> clubs,ListQuery query,long count) { }
    private final ClubRepository clubs;private final PlatformConsoleProjection projection;private final ClubSmsUsage sms;
    private final ClubClock clock;private final ClubTemplateProvisioner templates;
    public PlatformConsoleQueries(ClubRepository clubs,PlatformConsoleProjection projection,ClubSmsUsage sms,ClubClock clock,ClubTemplateProvisioner templates) {
        this.clubs=clubs;this.projection=projection;this.sms=sms;this.clock=clock;this.templates=templates;
    }
    @org.springframework.beans.factory.annotation.Autowired private PlatformProviders providers;
    public Club get(String id) { return clubs.findById(id).orElseThrow(()->new ApiException(ErrorCode.CLUB_NOT_FOUND)); }
    public String name(String id) { return get(id).name(); }
    public Page list(MultiValueMap<String,String> params) {
        var q=ListQuery.parse(PlatformClubContractGuards.CLUBS,params);return new Page(projection.page(q,PlatformClubContractGuards.CLUBS),q,projection.count(q,PlatformClubContractGuards.CLUBS));
    }
    public List<Club> templates() { return clubs.findAll().stream().filter(Club::template).sorted(Comparator.comparing(Club::name).thenComparing(Club::id)).toList(); }
    public Usage usage(Club c,String month) {
        YearMonth selected;
        try { selected=month==null?YearMonth.from(clock.now(c.id())):YearMonth.parse(month); }
        catch(DateTimeException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field","month")); }
        return new Usage(selected.toString(),projection.activeMembers(c.id()),sms.sent(c.id(),selected),projection.storageBytes(c.id()));
    }
    public Instant lastAdminAccess(Club c) { return projection.lastAdminAccess(c.id()); }
    public Checklist checklist(Club c) {
        boolean provider=providers.summaries(c).values().stream().anyMatch(p->p.enabled()&&p.configured());
        boolean branding=c.theme()!=null&&c.theme().logoUrl()!=null&&!c.theme().logoUrl().isBlank();
        var preset=clubs.findBySlug("club-template-default");
        branding=branding&&preset.map(p->!p.theme().colors().equals(c.theme().colors())).orElse(true);
        boolean complete;
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open(c.id())) { complete=templates.complete(c.defaultLocale()); }
        return new Checklist(!c.domains().isEmpty()&&c.domains().stream().allMatch(d->d.status()==Club.DomainStatus.VERIFIED),
                !c.modules().contains(Module.BILLING)||provider,branding,complete,!c.modules().contains(Module.FAQ)||projection.count(c.id(),"faq_entries")>0,
                c.legal()!=null&&c.legal().privacyPolicyUrl()!=null&&!c.legal().privacyPolicyUrl().isBlank(),lastAdminAccess(c)!=null);
    }
}
