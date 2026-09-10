package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.ConsentLedgerConverter;
import java.time.Instant;
import java.util.*;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Preserve prior evidence when a legacy projection first enters the S04 consent ledger. */
final class ConsentLedgers {
    private ConsentLedgers() { }
    static List<Map<String,Object>> entries(Map<String,Object> consent) {
        if(map(consent).containsKey("history")) return new ArrayList<>(rows(consent.get("history")));
        var entries=new ArrayList<Map<String,Object>>();
        for(String field:List.of("privacyPolicy","imageRights")) {
            var previous=map(map(consent).get(field));
            Object at=previous.getOrDefault("acceptedAt",previous.get("at"));
            if(at==null || previous.get("version")==null) continue;
            var entry=new LinkedHashMap<>(previous);
            entry.put("type",field.equals("privacyPolicy")?"PRIVACY_POLICY":"IMAGE_USE");
            entry.put("granted",field.equals("privacyPolicy") || Boolean.TRUE.equals(previous.getOrDefault("granted",previous.get("accepted"))));
            entry.put("acceptedAt",at);entry.putIfAbsent("source","LEGACY");entries.add(entry);
        }
        return entries;
    }
    static Map<String,Object> image(Map<String,Object> previous,boolean granted,String version,Instant at,String accountId) {
        var image=object("granted",granted,"at",at,"version",version,"byAccountId",accountId);
        if(!map(previous).containsKey("history")) {
            var consent=new LinkedHashMap<>(map(previous));consent.put("imageRights",image);return consent;
        }
        var ledger=entries(previous);
        ledger.add(object("type","IMAGE_USE","granted",granted,"version",version,"acceptedAt",at,
                "locale",com.agilityhub.core.shared.application.LocaleContext.current().getLanguage(),"source","PROFILE","byAccountId",accountId));
        return new ConsentLedgerConverter().read(ledger,null);
    }
}
