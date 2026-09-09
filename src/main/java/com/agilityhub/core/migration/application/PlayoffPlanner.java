package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.clubs.catalogs.application.MigrationCatalogAccess;
import com.agilityhub.core.identity.application.MigrationIdentityService;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;
import static com.agilityhub.core.migration.domain.MappingConfig.normalize;

/** Builds a complete private in-memory change set before apply; preview uses the same algorithm. */
@Service
public class PlayoffPlanner {
    private final CensusMigrationService census; private final MigrationCatalogAccess catalogs;
    private final MigrationIdentityService identities; private final ClubConfigService configs;
    private final CountryContacts contacts; private final ClubClock clubClock;
    public PlayoffPlanner(CensusMigrationService census,MigrationCatalogAccess catalogs,MigrationIdentityService identities,
            ClubConfigService configs,CountryContacts contacts,ClubClock clubClock) {
        this.census=census; this.catalogs=catalogs; this.identities=identities; this.configs=configs; this.contacts=contacts; this.clubClock=clubClock;
    }
    public record Change(String entity,String id,Map<String,Object> fields,PlayoffInput.Row source) { }
    public record Identity(String memberId,String email,String name,String locale,boolean active,Set<String> roles) { }
    public record Plan(List<Change> changes,List<Identity> identities,int maximumNumber,List<MigrationReport.Entry> rows) { }
    private record Candidate(PlayoffInput.Row row,String status,LocalDate joined,LocalDate left) { }
    public Plan plan(PlayoffInput input,MappingConfig mapping) {
        var state=new State(input,mapping); state.prepare(); return state.result();
    }
    private final class State {
        final PlayoffInput input; final MappingConfig mapping; final String club=TenantContext.require();
        final ClubConfig config=configs.get(club); final LocalDate today=clubClock.today(club);
        final ZoneId zone=ZoneId.of(config.club().timeZone());
        final List<MigrationReport.Entry> rows=new ArrayList<>(); final List<Change> changes=new ArrayList<>();
        final List<Identity> identityChanges=new ArrayList<>(); final Map<String,Change> people=new LinkedHashMap<>();
        final Map<String,String> memberBySource=new HashMap<>(), byDocument=new HashMap<>(), numbers=new HashMap<>(), chips=new HashMap<>(), emails=new HashMap<>();
        final Map<String,Map<String,Object>> storedMembers=new HashMap<>(),storedDogs=new HashMap<>(),storedGroups=new HashMap<>();
        final Map<String,Map<String,Object>> plans=new HashMap<>(), levels=new HashMap<>(); final List<Map<String,Object>> prices=catalogs.rows("prices");
        int maximum;
        State(PlayoffInput input,MappingConfig mapping) { this.input=input; this.mapping=mapping; rows.addAll(input.incidents()); }
        void incident(PlayoffInput.Row row,String entity,String outcome,String code) { rows.add(new MigrationReport.Entry(row.file(),row.row(),entity,outcome,code)); }
        void warn(PlayoffInput.Row row,String code) { incident(row,"members","WARNING",code); }
        String id(String entity,String source) { return UUID.nameUUIDFromBytes((club+":"+entity+":"+source).getBytes(StandardCharsets.UTF_8)).toString(); }
        void prepare() {
            for (var row:catalogs.rows("plans")) { plans.put(string(row.get("code")),row); }
            for (var row:catalogs.rows("levels")) { levels.put(string(row.get("code")),row); }
            for (var member:census.snapshot("members")) {
                String id=string(member.get("_id")); storedMembers.put(id,member);
                Object aliases=map(member.get("externalIds")).get("playoff");
                if (aliases instanceof List<?> ids) { ids.forEach(source -> memberBySource.put(source.toString(),id)); }
                String source=string(map(member.get("sourceIds")).get("playoffMemberId")); if (source!=null) { memberBySource.put(source,id); }
                String doc=string(map(member.get("idDocument")).get("number")); if (doc!=null) { byDocument.put(doc,id); }
                if (member.get("memberNumber")!=null) { numbers.put(member.get("memberNumber").toString(),id); }
            }
            for (var dog:census.snapshot("dogs")) {
                storedDogs.put(string(dog.get("_id")),dog);
                if (dog.get("chip")!=null) { chips.put(string(dog.get("chip")),string(dog.get("_id"))); }
            }
            for (var group:census.snapshot("family_groups")) { storedGroups.put(string(group.get("_id")),group); }
            var candidates=new ArrayList<Candidate>(); var seen=new HashSet<String>();
            for (var row:input.files().get("members")) {
                try {
                    if (row.get("id").isEmpty() || !seen.add(row.get("id"))) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                    if (!row.get("number").isEmpty()) { maximum=Math.max(maximum,Integer.parseInt(row.get("number"))); }
                    String status=mapping.statuses().get(normalize(row.get("status")));
                    if (status==null) { throw new ApiException(ErrorCode.MAPPING_INVALID); }
                    LocalDate left=date(row.get("left"));
                    if (status.equals("SKIP") || status.equals("LEFT") && (left==null || left.isBefore(today.minusYears(config.get("migration.leftMaxYears",Integer.class))))) {
                        incident(row,"members","SKIPPED",""); continue;
                    }
                    LocalDate joined=date(row.get("joined"));
                    if (joined==null || row.get("firstName").isEmpty()) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                    candidates.add(new Candidate(row,status,joined,left));
                } catch (ApiException | IllegalArgumentException bad) { incident(row,"members","ERROR",bad instanceof ApiException api ? api.code().name() : "INPUT_SCHEMA_MISMATCH"); }
            }
            candidates.sort(Comparator.comparing((Candidate c) -> !c.status().equals("ACTIVE")).thenComparing(Candidate::joined).thenComparingInt(c -> c.row().row()));
            for (var candidate:candidates) {
                try { memberAndDog(candidate); }
                catch (ApiException | IllegalArgumentException bad) { incident(candidate.row(),"members","ERROR",bad instanceof ApiException api ? api.code().name() : "INPUT_SCHEMA_MISMATCH"); }
            }
            Set<String> sourceIds=input.files().get("members").stream().map(r -> r.get("id")).collect(java.util.stream.Collectors.toSet());
            for (String file:List.of("plans","levels","groups")) {
                for (var row:input.files().get(file)) { if (!sourceIds.contains(row.get("id"))) { incident(row,file,"ERROR","INPUT_SCHEMA_MISMATCH"); } }
            }
            groups();
        }
        Plan result() { return new Plan(changes,identityChanges,maximum,rows); }
        void memberAndDog(Candidate c) {
            var row=c.row(); String source=row.get("id"); String email=row.get("email").toLowerCase(Locale.ROOT);
            String document=(normalize(row.get("hasPassport")).equals("s") ? row.get("passport") : row.get("document")).replaceAll("[\\s.-]", "").toUpperCase(Locale.ROOT);
            String person=document.isEmpty() ? email.isEmpty() ? "id:"+source : "email:"+email : "document:"+document;
            var personChange=people.get(person);
            String memberId=personChange==null ? memberBySource.getOrDefault(source,id("member",source)) : personChange.id();
            if (!document.isEmpty() && byDocument.containsKey(document) && !byDocument.get(document).equals(memberId)) { throw new ApiException(ErrorCode.ID_DOCUMENT_ALREADY_EXISTS); }
            String dogId=id("dog",source);
            if (!row.get("chip").isEmpty() && chips.containsKey(row.get("chip")) && !chips.get(row.get("chip")).equals(dogId)) { throw new ApiException(ErrorCode.CHIP_ALREADY_EXISTS); }
            var old=storedMembers.get(memberId);
            if (old!=null && (old.get("sourceIds")==null || old.get("erasedAt")!=null)) { throw new ApiException(ErrorCode.CLUB_NOT_EMPTY); }
            String surname=row.get("surname"); var matcher=java.util.regex.Pattern.compile("\\(([^()]*)\\)").matcher(surname);
            String fromSurname=matcher.find() ? matcher.group(1).strip() : "";
            surname=surname.replaceAll("\\([^()]*\\)", "").strip().replaceAll("\\s+", " ");
            if (personChange==null) {
                Map<String,Object> fields=new LinkedHashMap<>();
                fields.put("sourceIds",object("playoffMemberId",source,"playoffNumber",row.get("number")));
                var aliases=new ArrayList<String>();
                Object previous=map(old==null ? null : old.get("externalIds")).get("playoff");
                if (previous instanceof List<?> list) { list.forEach(item -> aliases.add(item.toString())); }
                if (!aliases.contains(source)) { aliases.add(source); }
                fields.put("externalIds",object("playoff",aliases));
                String[] names=surname.split(" ",2);
                fields.putAll(object("firstName",row.get("firstName"),"lastName1",names[0],"lastName2",names.length>1 ? names[1] : "",
                        "status",c.status(),"joinedAt",instant(c.joined()),"contactEmails",contactEmails(row),"phones",phones(row),
                        "address",address(row),"gender",Map.of("femení","FEMALE","masculí","MALE","altres / no binari","OTHER").get(normalize(row.get("gender"))),
                        "internalNotes",row.get("notes"),"notificationPreferences",object("essentialOnly",normalize(row.get("notifications")).equals("no"))));
                String type=normalize(row.get("hasPassport")).equals("s") ? "PASSPORT" : document.matches("[XYZ].*") ? "NIE" : "DNI";
                if (!document.isEmpty() && !contacts.document(type,document)) { warn(row,"INVALID_ID_DOCUMENT"); }
                fields.put("idDocument",document.isEmpty() ? null : object("type",type,"number",document));
                Integer number=row.get("number").isEmpty() ? null : Integer.valueOf(row.get("number"));
                if (number!=null && numbers.containsKey(number.toString()) && !numbers.get(number.toString()).equals(memberId)) { number=null; warn(row,"NUMBER_CONFLICT"); }
                fields.put("memberNumber",number); if (number!=null) { numbers.put(number.toString(),memberId); }
                LocalDate birth=date(row.get("birthDate"));
                if (birth!=null && birth.isAfter(today.minusYears(mapping.ageWarningYears()))) { warn(row,"AGE_SUSPECT"); }
                if (birth!=null && birth.isAfter(today.minusYears(mapping.suspectBirthYears())) && row.get("dogBirth").isEmpty()) { birth=null; warn(row,"BIRTHDATE_SUSPECT"); }
                fields.put("birthDate",birth==null ? null : birth.toString());
                fields.put("leaveDate",c.left()==null ? null : c.left().toString());
                fields.put("leftAt",c.status().equals("LEFT") ? instant(c.left()) : null);
                fields.put("leftReason",c.status().equals("LEFT") ? "MIGRATED" : null);
                fields.put("bookingBlock",normalize(row.get("status")).equals("bloqueado") ? object("active",true,"reason","Migrated from Playoff: blocked","since",instant(c.joined())) : object("active",false));
                // A legacy marker records provenance; it never claims consent to a current policy.
                if (old==null) { fields.put("consents",object("privacyPolicy",object("version","LEGACY","acceptedAt",instant(c.joined()),"source","MIGRATED"))); }
                fields.put("paymentMethod",payment(row));
                Set<String> roles=new HashSet<>(Set.of("MEMBER")); linkPlan(row,fields,roles);
                for (var team:input.files().get("team")) {
                    if (team.get("number").equals(row.get("number"))) {
                        if (!Set.of("MEMBER","INSTRUCTOR","ADMIN").contains(team.get("role"))) { throw new ApiException(ErrorCode.MAPPING_INVALID); }
                        roles.add(team.get("role"));
                    }
                }
                if (!email.isEmpty() && validEmail(email)) {
                    var identity=identities.preview(email);
                    if (emails.containsKey(email) && !emails.get(email).equals(memberId) || identity!=null && identity.memberId()!=null && !identity.memberId().equals(memberId)) { warn(row,"EMAIL_SHARED"); }
                    else if (identity!=null && !identity.active()) { warn(row,"ACCOUNT_BLOCKED"); }
                    else {
                        emails.put(email,memberId);
                        identityChanges.add(new Identity(memberId,email,row.get("firstName")+" "+surname,config.club().defaultLocale(),c.status().equals("ACTIVE"),roles));
                        incident(row,"accounts",identity==null ? "CREATED" : "UPDATED","");
                    }
                } else { incident(row,"accounts","SKIPPED",email.isEmpty() ? "" : "VALIDATION_ERROR"); }
                personChange=new Change("members",memberId,fields,row); people.put(person,personChange); changes.add(personChange);
                if (!document.isEmpty()) { byDocument.put(document,memberId); } incident(row,"members",old==null ? "CREATED" : "UPDATED","");
            } else {
                @SuppressWarnings("unchecked") var aliases=(List<String>)map(personChange.fields().get("externalIds")).get("playoff");
                if (!aliases.contains(source)) { aliases.add(source); }
                incident(row,"members","SKIPPED","");
            }
            memberBySource.put(source,memberId);
            String dogName=row.get("dogName");
            if (dogName.isEmpty() && !fromSurname.isEmpty()) { dogName=fromSurname; warn(row,"DOG_NAME_FROM_SURNAME"); }
            if (dogName.isEmpty()) { dogName=mapping.inferredDogPrefix()+row.get("firstName"); warn(row,"DOG_INFERRED"); }
            if (row.get("chip").isEmpty()) { warn(row,"CHIP_MISSING"); } else { chips.put(row.get("chip"),dogId); }
            var dog=object("memberId",memberId,"name",dogName,"breed",row.get("breed"),"sex",Map.of("femella","FEMALE","mascle","MALE").get(normalize(row.get("dogSex"))),
                    "handlerName",row.get("handler"),"status",c.status().equals("LEFT") ? "INACTIVE" : "ACTIVE","registeredAt",instant(c.joined()),
                    "sourceIds",object("playoffMemberId",source,"playoffDogIndex",0),"externalIds",object("playoff",source),
                    "instructorNote",object("text",row.get("objectives")),"licenses",licenses(row));
            dog.put("birthDate",row.get("dogBirth").isEmpty() ? null : date(row.get("dogBirth")).toString());
            dog.put("chip",row.get("chip").isEmpty() ? null : row.get("chip"));
            if (c.status().equals("LEFT")) { dog.putAll(object("deactivatedAt",instant(c.left()),"deactivationReason","MEMBER_LEFT")); }
            linkLevel(row,dog);
            if (!row.get("photo").isEmpty()) { warn(row,"MAPPING_INVALID"); }
            changes.add(new Change("dogs",dogId,dog,row)); incident(row,"dogs",storedDogs.containsKey(dogId) ? "UPDATED" : "CREATED","");
        }
        java.util.Date instant(LocalDate date) { return date==null ? null : java.util.Date.from(date.atStartOfDay(zone).toInstant()); }
        List<Map<String,Object>> contactEmails(PlayoffInput.Row row) {
            var values=new ArrayList<Map<String,Object>>();
            for (String key:List.of("email","email2")) { String value=row.get(key).toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) { if (validEmail(value)) { var email=object("email",value,"bounced",false); if (!values.contains(email)) { values.add(email); } } else { warn(row,"VALIDATION_ERROR"); } }
            }
            return values;
        }
        List<Map<String,Object>> phones(PlayoffInput.Row row) {
            var values=new ArrayList<Map<String,Object>>();
            for (String key:List.of("phone","phone2")) { if (!row.get(key).isEmpty()) {
                try { values.add(contacts.phone(null,row.get(key),null)); } catch (ApiException invalid) { warn(row,"INVALID_PHONE"); }
            } }
            if (values.isEmpty()) { warn(row,"INVALID_PHONE"); } return values;
        }
        Map<String,Object> address(PlayoffInput.Row row) {
            if (row.get("postalCode").isEmpty() || row.get("street").isEmpty()) { warn(row,"VALIDATION_ERROR"); }
            return object("street",row.get("street"),"postalCode",row.get("postalCode"),"city",row.get("city"),"province",normalize(row.get("province")),
                    "country",normalize(row.get("country")).equals("espanya") ? "ES" : row.get("country"));
        }
        Map<String,Object> payment(PlayoffInput.Row row) {
            String payment=normalize(row.get("payment"));
            if (payment.equals("domiciliació bancària")) {
                String iban=row.get("iban").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
                if (iban.isEmpty()) { warn(row,"NO_BANK_ACCOUNT"); }
                else if (!contacts.iban(iban)) { warn(row,"IBAN_INVALID"); iban=""; }
                return object("type","SEPA_DD","iban",iban.isEmpty() ? null : iban,"holderName",row.get("holder").isEmpty() ? row.get("firstName")+" "+row.get("surname").replaceAll("\\([^()]*\\)", "").strip() : row.get("holder"));
            }
            if (!Set.of("transferència","paga en efectiu").contains(payment)) { warn(row,"CARD_NOT_MIGRATED"); }
            return object("type","MANUAL","channel",payment.equals("transferència") ? "transfer" : "cash");
        }
        List<Map<String,Object>> licenses(PlayoffInput.Row row) {
            var values=new ArrayList<Map<String,Object>>();
            for (String org:List.of("rsce","fcag")) { if (!row.get(org).isEmpty()) { values.add(object("organisation",org.toUpperCase(Locale.ROOT),"number",row.get(org),
                    "category",row.get("category"),"grade",row.get("grade"),"division",row.get("division"))); } }
            return values;
        }
        void linkPlan(PlayoffInput.Row row,Map<String,Object> fields,Set<String> roles) {
            var matches=input.files().get("plans").stream().filter(r -> r.get("id").equals(row.get("id"))).toList();
            fields.put("planId",null); fields.put("priceId",null); fields.put("nextInvoiceDate",null);
            if (matches.size()!=1) { warn(row,"PLAN_UNMAPPED"); return; }
            String key=normalize(matches.getFirst().get("plan")); String code=mapping.plans().get(key);
            if (mapping.instructorPlans().contains(key)) { roles.add("INSTRUCTOR"); }
            if (mapping.familyPlans().contains(key)) { warn(row,"EMAIL_SHARED"); }
            if (code==null) { warn(row,mapping.unresolvedPlans().contains(key) ? "PLAN_UNMAPPED" : "LEGACY_PLAN"); return; }
            var plan=plans.get(code);
            if (plan==null || !Boolean.TRUE.equals(plan.get("active"))) { warn(row,"LEGACY_PLAN"); return; }
            String planId=string(plan.get("_id")); fields.put("planId",planId);
            boolean monthly="MONTHLY".equals(plan.get("type"));
            if (monthly && "ACTIVE".equals(fields.get("status"))) { fields.put("nextInvoiceDate",today.withDayOfMonth(1).plusMonths(1).toString()); }
            String concept=monthly ? "MAINTENANCE".equals(plan.get("billingMode")) ? "MAINTENANCE_FEE" : "MONTHLY_FEE" : "PACK";
            var current=prices.stream().filter(p -> planId.equals(p.get("planId")) && concept.equals(p.get("concept")) && !businessDate(p.get("validFrom")).isAfter(today)
                    && (p.get("validTo")==null || !businessDate(p.get("validTo")).isBefore(today))).toList();
            if (current.size()==1) { fields.put("priceId",current.getFirst().get("_id")); } else { warn(row,"PRICE_NOT_FOUND"); }
        }
        void linkLevel(PlayoffInput.Row row,Map<String,Object> dog) {
            var matches=input.files().get("levels").stream().filter(r -> r.get("id").equals(row.get("id"))).toList();
            var assigned=new ArrayList<PlayoffInput.Row>(); dog.put("levelId",null);
            for (var match:matches) {
                String key=normalize(match.get("level"));
                if (mapping.levelFlags().containsKey(key)) { if (mapping.levelFlags().get(key).equals("THERAPY")) { warn(row,"PLAN_UNMAPPED"); } continue; }
                if (mapping.unresolvedLevels().contains(key) || !mapping.levels().containsKey(key)) { warn(row,"LEVEL_PENDING"); continue; }
                assigned.add(match);
            }
            if (assigned.size()!=1) { warn(row,"LEVEL_PENDING"); return; }
            var source=assigned.getFirst(); var level=levels.get(mapping.levels().get(normalize(source.get("level"))));
            if (level==null || !Boolean.TRUE.equals(level.get("active"))) { warn(row,"LEVEL_PENDING"); return; }
            var at=instant(date(source.get("assigned"))); if (at==null) { warn(row,"INPUT_SCHEMA_MISMATCH"); }
            dog.putAll(object("levelId",level.get("_id"),"levelAssignedAt",at,"levelHistory",List.of(object("levelId",level.get("_id"),"from",at,"reason","MIGRATED"))));
        }
        void groups() {
            var grouped=input.files().get("groups").stream().collect(java.util.stream.Collectors.groupingBy(r -> r.get("groupId"),LinkedHashMap::new,java.util.stream.Collectors.toList()));
            for (var group:grouped.entrySet()) {
                var source=group.getValue().getFirst(); String groupId=id("group",group.getKey());
                var ids=group.getValue().stream().map(r -> memberBySource.get(r.get("id"))).distinct().toList();
                String holder=memberBySource.get(source.get("holderId"));
                if (group.getKey().isEmpty() || ids.contains(null) || ids.size()<2 || !ids.contains(holder)
                        || group.getValue().stream().anyMatch(r -> !r.get("holderId").equals(source.get("holderId")))) {
                    incident(source,"familyGroups","ERROR","INPUT_SCHEMA_MISMATCH"); continue;
                }
                var affected=changes.stream().filter(c -> c.entity().equals("members") && ids.contains(c.id())).toList();
                if (affected.size()!=ids.size() || affected.stream().anyMatch(c -> !"ACTIVE".equals(c.fields().get("status"))
                        || c.fields().containsKey("familyGroupId") || storedMembers.containsKey(c.id()) && storedMembers.get(c.id()).get("familyGroupId")!=null
                        && !groupId.equals(storedMembers.get(c.id()).get("familyGroupId")))) {
                    incident(source,"familyGroups","ERROR","FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP"); continue;
                }
                affected.forEach(c -> c.fields().put("familyGroupId",groupId));
                changes.add(new Change("family_groups",groupId,object("holderMemberId",holder,"memberIds",ids,"status","ACTIVE",
                        "sourceIds",object("playoffGroupId",group.getKey()),"externalIds",object("playoff",group.getKey())),source));
                incident(source,"familyGroups",storedGroups.containsKey(groupId) ? "UPDATED" : "CREATED","");
            }
        }
    }
    static boolean validEmail(String email) { return email.length()<=254 && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"); }
    static LocalDate date(String value) {
        if (value.isEmpty()) { return null; }
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException notIso) { return LocalDate.parse(value,DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)); }
    }
    static LocalDate businessDate(Object value) {
        return value instanceof java.util.Date date ? date.toInstant().atZone(ZoneOffset.UTC).toLocalDate() : date(value.toString());
    }
}
