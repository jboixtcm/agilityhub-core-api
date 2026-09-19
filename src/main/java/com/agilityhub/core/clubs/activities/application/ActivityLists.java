package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.clubs.activities.persistence.*;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.*;

@Component
public class ActivityLists implements ListProvider {
    private final ActivityContext context;
    public ActivityLists(ActivityContext context) { this.context=context; }
    public Set<String> keys() { return Set.of("activities","activity-registrations"); }
    public static MultiValueMap<String,String> params(MultiValueMap<String,String> original) {
        var result=new LinkedMultiValueMap<>(original);
        if(original.getOrDefault("filter",List.of()).stream().noneMatch(f -> f.startsWith("deleted:"))) result.add("filter","deleted:eq:false");
        return result;
    }
    public ListDataset dataset(String key) {
        context.require(); boolean activity=key.equals("activities");
        var fields=activity?List.of("id","title","date","rings","registrations","state","type","slug","registrationTo","createdAt")
                :List.of("id","registrationId","member","state","position","origin","registeredAt","cancelledAt","cancelReason");
        var filters=new LinkedHashMap<String,ListDefinition.Field>(); filters.put("id",new ListDefinition.Field("_id",ListDefinition.Type.TEXT));
        if(activity) {
            for(String f:List.of("state","type","date","ringId","levelId","deleted","registrationOpen")) filters.put(f,new ListDefinition.Field(
                    f.equals("ringId")?"ringIds":f.equals("levelId")?"levelIds":f,f.equals("date")?ListDefinition.Type.DATE:Set.of("deleted","registrationOpen").contains(f)?ListDefinition.Type.BOOLEAN:ListDefinition.Type.TEXT));
        } else for(String f:List.of("activityId","state","origin","registeredAt","memberId")) filters.put(f,new ListDefinition.Field(f,f.equals("registeredAt")?ListDefinition.Type.INSTANT:ListDefinition.Type.TEXT));
        var sorts=activity?Map.of("date","date","title","title","state","state","createdAt","createdAt"):Map.of("registeredAt","registeredAt","position","position","memberLastName","memberLastName");
        var columns=activity?List.of("title","date","rings","registrations","state","type","slug","registrationTo"):List.of("member","state","position","origin","registeredAt","cancelledAt","cancelReason");
        var defaults=activity?columns.subList(0,5):columns.subList(0,5);
        var definition=new ListDefinition(key,filters,sorts,activity?List.of("title"):List.of("member.fullName"),columns,defaults,List.of(activity?"date,desc":"registeredAt,asc"),Set.copyOf(fields));
        var stages=new ArrayList<Document>();
        if(activity) {
            String lang=LocaleContext.current().toLanguageTag(),fallback=context.config().club().defaultLocale();
            Object title=new Document("$ifNull",List.of("$title.values."+lang,new Document("$ifNull",List.of("$title."+lang,new Document("$ifNull",List.of("$title.values."+fallback,"$title."+fallback))))));
            stages.add(new Document("$set",new Document("title",title).append("registrations","$counters")
                    .append("deleted",new Document("$and",List.of(new Document("$eq",List.of("$state","CANCELLED")),new Document("$eq",List.of("$cancellation.reason","DELETED")))))
                    .append("registrationOpen",new Document("$and",List.of(new Document("$eq",List.of("$state","PUBLISHED")),new Document("$lte",List.of(localDate("$registrationFrom",false),Date.from(context.clock.instant()))),new Document("$gt",List.of(localDate("$registrationTo",true),Date.from(context.clock.instant()))))))));
            stages.add(lookup("rings",new Document("$in",List.of("$_id","$$refs")),new Document("refs","$ringIds"),"ringRows"));
            stages.add(new Document("$set",new Document("rings",new Document("$map",new Document("input","$ringRows").append("as","ring")
                    .append("in",new Document("id","$$ring._id").append("name","$$ring.name").append("color","$$ring.color"))))));
        } else {
            stages.add(lookup("members",new Document("$eq",List.of("$_id","$$ref")),new Document("ref","$memberId"),"memberRows"));
            stages.add(new Document("$set",new Document("person",new Document("$arrayElemAt",List.of("$memberRows",0)))));
            stages.add(new Document("$set",new Document("registrationId","$_id").append("memberLastName","$person.lastName1")
                    .append("member",new Document("id","$memberId").append("fullName",new Document("$trim",new Document("input",new Document("$concat",List.of(
                            new Document("$ifNull",List.of("$person.firstName",""))," ",new Document("$ifNull",List.of("$person.lastName1",""))," ",new Document("$ifNull",List.of("$person.lastName2","")))))))
                            .append("memberNumber",new Document("$convert",new Document("input","$person.memberNumber").append("to","string").append("onNull", "")))
                            .append("phones",new Document("$ifNull",List.of("$person.phones",List.of())))
                            .append("emails",new Document("$map",new Document("input",new Document("$ifNull",List.of("$person.contactEmails",List.of()))).append("as","email").append("in","$$email.email"))))));
        }
        var output=new LinkedHashMap<String,Object>(); fields.forEach(f -> output.put(f,1)); output.put("id","$_id"); output.put("_id",0);
        return new ListDataset(definition,activity?"activities":"activity_registrations",stages,output,Set.of("date","registrationTo","position","cancelledAt","cancelReason"),(field,value) -> Objects.toString(value,""));
    }
    private Document localDate(String field,boolean nextDay) {
        Document date=new Document("$dateFromString",new Document("dateString",field).append("timezone",context.zone().getId()).append("onNull",null));
        return nextDay?new Document("$dateAdd",new Document("startDate",date).append("unit","day").append("amount",1).append("timezone",context.zone().getId())):date;
    }
    private Document lookup(String collection,Document expression,Document variables,String alias) {
        variables.append("club","$clubId");
        return new Document("$lookup",new Document("from",collection).append("let",variables).append("pipeline",List.of(new Document("$match",new Document("$expr",new Document("$and",List.of(new Document("$eq",List.of("$clubId","$$club")),expression)))))).append("as",alias));
    }
}
