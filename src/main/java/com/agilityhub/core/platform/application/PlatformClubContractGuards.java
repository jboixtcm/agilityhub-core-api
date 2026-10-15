package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/** Read-only contract guards. Console actions never provision a club or contact a provider in E10-T01. */
@Component
public class PlatformClubContractGuards {
    private final ClubRepository clubs;
    private final ParameterCatalog parameters;
    public PlatformClubContractGuards(ClubRepository clubs,ParameterCatalog parameters) {
        this.clubs=clubs; this.parameters=parameters;
    }
    public void club(String id) { clubs.findById(id).orElseThrow(()->new ApiException(ErrorCode.CLUB_NOT_FOUND)); }
    public void domain(String id,String host) {
        var club=clubs.findById(id).orElseThrow(()->new ApiException(ErrorCode.CLUB_NOT_FOUND));
        String normalized=com.agilityhub.core.platform.domain.HostNames.normalize(host);
        if(club.domains().stream().noneMatch(domain->domain.host().equals(normalized))) throw new ApiException(ErrorCode.NOT_FOUND);
    }
    public void template(String id) {
        if(clubs.findById(id).isEmpty()) throw new ApiException(ErrorCode.TEMPLATE_CLUB_NOT_FOUND);
    }
    public void parameter(String key) { if(!parameters.entries().containsKey(key)) throw new ApiException(ErrorCode.UNKNOWN_PARAMETER); }
    public static final Set<String> FIELDS=Set.of("id","name","slug","status","countryProfile","timeZone","modules","template","lastAdminAccessAt","usage","onboardingChecklist");
    public static final ListDefinition CLUBS=new ListDefinition("platformClubs",Map.of(
            "status",new ListDefinition.Field("status",ListDefinition.Type.TEXT),
            "countryProfile",new ListDefinition.Field("countryProfile",ListDefinition.Type.TEXT),
            "module",new ListDefinition.Field("modules",ListDefinition.Type.TEXT),
            "template",new ListDefinition.Field("template",ListDefinition.Type.BOOLEAN)),
            Map.of("name","name","slug","slug"),List.of("name","slug"),List.copyOf(FIELDS),List.of("name","slug"),List.of("name,asc"),FIELDS);
    public void list(MultiValueMap<String,String> query) { ListQuery.parse(CLUBS,query); }
}
