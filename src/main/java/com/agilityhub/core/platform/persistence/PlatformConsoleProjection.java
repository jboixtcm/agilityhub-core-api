package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** R-17-12: global configuration queries and aggregate-only reads of club data. */
@Repository
public class PlatformConsoleProjection extends GlobalRepository<Club> {
    public PlatformConsoleProjection(MongoTemplate mongo) { super(mongo,Club.class); }
    public List<Club> page(ListQuery query,ListDefinition definition) { return mongo.find(query(query,definition).with(sort(query)).skip((long)query.page()*query.size()).limit(query.size()),Club.class); }
    public long count(ListQuery query,ListDefinition definition) { return mongo.count(query(query,definition),Club.class); }
    private Query query(ListQuery query,ListDefinition definition) {
        var filters=new ArrayList<Criteria>();
        for(Filter filter:query.filters()) {
            var c=Criteria.where(definition.field(filter.field()).path());Object v=filter.value();
            if(definition.field(filter.field()).type()==ListDefinition.Type.INSTANT) v=v instanceof List<?> l?l.stream().map(x->Date.from(Instant.parse(x.toString()))).toList():Date.from(Instant.parse(v.toString()));
            c=switch(filter.op()) {
                case eq -> c.is(v);case ne -> c.ne(v);case in -> c.in((List<?>)v);case nin -> c.nin((List<?>)v);
                case gt -> c.gt(v);case gte -> c.gte(v);case lt -> c.lt(v);case lte -> c.lte(v);
                case contains -> c.regex(Pattern.quote(v.toString()),"i");case startsWith -> c.regex("^"+Pattern.quote(v.toString()),"i");
                case exists -> c.exists((Boolean)v);case between -> c.gte(((List<?>)v).get(0)).lte(((List<?>)v).get(1));
            };filters.add(c);
        }
        if(!query.q().isEmpty()) filters.add(new Criteria().orOperator(Criteria.where("name").regex(Pattern.quote(query.q()),"i"),Criteria.where("slug").regex(Pattern.quote(query.q()),"i")));
        return filters.isEmpty()?new Query():Query.query(new Criteria().andOperator(filters));
    }
    private Sort sort(ListQuery query) {
        var orders=new ArrayList<Sort.Order>();for(String s:query.sort()) { var p=s.split(",");orders.add(new Sort.Order(Sort.Direction.fromString(p[1]),p[0])); }
        orders.add(Sort.Order.asc("_id"));return Sort.by(orders);
    }
    public com.agilityhub.core.shared.application.contract.ApiContracts.ListPage<Map<String,Object>> telemetry(ListQuery input,ListDefinition definition,boolean security) {
        Query query=query(input,definition);String collection=security?"security_events":"audit_entries";
        if(!security) { var privacy=new Criteria().andOperator(Criteria.where("memberId").is(null),Criteria.where("impersonatedMemberId").is(null),
                new Criteria().orOperator(
                    new Criteria().andOperator(Criteria.where("entityType").is("Club"),Criteria.where("action").in("CLUB_UPDATED","CLUB_MODULES_CHANGED","CLUB_STATUS_CHANGED",
                            "CLUB_CREATED","CLUB_PAYMENT_PROVIDER_CHANGED","CLUB_ADMIN_INVITED")),
                    new Criteria().andOperator(Criteria.where("entityType").is("Parameter"),Criteria.where("action").is("PARAMETER_CHANGED")),
                    new Criteria().andOperator(Criteria.where("entityType").is("Account"),Criteria.where("action").is("PLATFORM_ROLES_CHANGED")),
                    new Criteria().andOperator(Criteria.where("entityType").in("Level","Ring","Plan","Price","FaqEntry","MessageTemplate"),Criteria.where("action").is("CATALOG_CHANGED"))));
            query=new BasicQuery(new Document("$and",List.of(query.getQueryObject(),privacy.getCriteriaObject())));
        }
        long count=mongo.count(query,collection);
        var allowed=security?List.of("type","accountId","clubId","route","at"):List.of("clubId","at","actorAccountId","actorName","actorRole","origin","entityType","entityId","action","changes","reason","details");
        query.fields().include("_id");for(String field:allowed) query.fields().include(field);
        var rows=mongo.find(query.with(input.sort().isEmpty()?Sort.by(Sort.Direction.DESC,"at","_id"):sort(input)).skip((long)input.page()*input.size()).limit(input.size()),Document.class,collection);
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            Map<String,Object> fields=new LinkedHashMap<>();fields.put("id",row.getString("_id"));
            for(String field:allowed) fields.put(field,normalize(row.get(field)));
            if(!security) {
                if(fields.get("changes")==null) fields.put("changes",List.of());
                if(fields.get("actorRole")==null) fields.put("actorRole","SYSTEM");
                if(fields.get("origin")==null) fields.put("origin","SYSTEM");
                fields=com.agilityhub.core.platform.domain.audit.AuditReadMasker.sanitize(fields);
            }
            result.add(fields);
        }
        return new com.agilityhub.core.shared.application.contract.ApiContracts.ListPage<>(result,input.page(),input.size(),count,
                (int)((count+input.size()-1)/input.size()),input.filters());
    }
    private Object normalize(Object value) {
        if(value instanceof java.util.Date d) return d.toInstant().toString();
        if(value instanceof Map<?,?> m) {var result=new LinkedHashMap<String,Object>();m.forEach((k,v)->result.put(k.toString(),normalize(v)));return result;}
        if(value instanceof List<?> l) return l.stream().map(this::normalize).toList();return value;
    }
    public long activeMembers(String club) { return mongo.count(Query.query(Criteria.where("clubId").is(club).and("status").is("ACTIVE")),"members"); }
    public long providerUsers(String club,String method) { return mongo.count(Query.query(Criteria.where("clubId").is(club).and("paymentMethod.type").is(method).and("status").ne("ERASED")),"members"); }
    /**
     * S17 §2/§6 usage: the bytes of the club's stored files, from the upload records whose object was checked and claimed
     * (`attachment_uploads.boundEntity`); a removed record stops counting. Tenant-scoped by `clubId`, an aggregate only.
     */
    public long storageBytes(String club) {
        var rows=mongo.aggregate(org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.match(Criteria.where("clubId").is(club).and("boundEntity").ne(null)),
                org.springframework.data.mongodb.core.aggregation.Aggregation.group().sum("sizeBytes").as("total")),"attachment_uploads",Document.class).getMappedResults();
        return rows.isEmpty()||!(rows.getFirst().get("total") instanceof Number total)?0L:total.longValue();
    }
    public long count(String club,String collection) { return mongo.count(Query.query(Criteria.where("clubId").is(club)),collection); }
    public Instant lastAdminAccess(String club) {
        var row=mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("roles").is("ADMIN").and("status").is("ACTIVE"))
                .with(Sort.by(Sort.Direction.DESC,"lastAccessAt")),Document.class,"memberships");
        return row==null||row.getDate("lastAccessAt")==null?null:row.getDate("lastAccessAt").toInstant();
    }
}
