package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.PlatformConsoleProjection;
import com.agilityhub.core.shared.application.contract.ApiContracts.*;
import com.agilityhub.core.shared.application.lists.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
public class PlatformAuditQueries {
    private final PlatformConsoleProjection projection;
    public PlatformAuditQueries(PlatformConsoleProjection projection) {this.projection=projection;}
    public ListPage<Map<String,Object>> audit(MultiValueMap<String,String> params) {return read(params,false);}
    public ListPage<Map<String,Object>> security(MultiValueMap<String,String> params) {return read(params,true);}
    private ListPage<Map<String,Object>> read(MultiValueMap<String,String> params,boolean security) {
        var fields=new LinkedHashMap<String,ListDefinition.Field>();
        for(String field:security?List.of("clubId"):List.of("clubId","action","entityType","entityId","memberId","actorAccountId","actorRole","impersonatedMemberId","origin"))
            fields.put(field,new ListDefinition.Field(field,ListDefinition.Type.TEXT));
        if(!security) fields.put("at",new ListDefinition.Field("at",ListDefinition.Type.INSTANT,Set.of(FilterOperator.between)));
        var definition=new ListDefinition("platformAudit",fields,security?Map.of():Map.of("at","at"),List.of(),List.of(),List.of(),security?List.of():List.of("at,desc"),Set.of());
        var query=ListQuery.parse(definition,params);return projection.telemetry(query,definition,security);
    }
}
