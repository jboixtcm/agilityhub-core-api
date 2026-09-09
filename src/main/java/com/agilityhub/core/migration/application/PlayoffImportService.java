package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.clubs.catalogs.application.MigrationCatalogAccess;
import com.agilityhub.core.identity.application.MigrationIdentityService;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Plans the complete census before writing; dry runs only read the target tenant. */
@Service
public class PlayoffImportService {
    private final CensusMigrationService census;
    private final MigrationCatalogAccess catalogs;
    private final MigrationIdentityService identity;
    private final MigrationClubAccess clubs;
    private final ClubConfigService configs;
    private final CountryContacts countries;
    private final ClubClock clubClock;
    private final Clock clock;
    private final MigrationRunRepository runs;
    private final MigrationApplyService writer;
    private final TransactionTemplate transactions;

    public PlayoffImportService(CensusMigrationService census, MigrationCatalogAccess catalogs,
            MigrationIdentityService identity, MigrationClubAccess clubs, ClubConfigService configs,
            CountryContacts countries, ClubClock clubClock, Clock clock, MigrationRunRepository runs,
            MigrationApplyService writer, PlatformTransactionManager manager) {
        this.census=census; this.catalogs=catalogs; this.identity=identity; this.clubs=clubs; this.configs=configs;
        this.countries=countries; this.clubClock=clubClock; this.clock=clock; this.runs=runs; this.writer=writer;
        transactions=new TransactionTemplate(manager);
    }

    public MigrationReport run(Path dir, MappingConfig mapping, String slug, boolean dryRun, boolean production) {
        var input=PlayoffInput.read(dir,mapping);
        if (input.incidents().stream().anyMatch(e -> e.outcome().equals("ERROR"))) { return new MigrationReport(dryRun,input.incidents()); }
        try (var scope=TenantContext.open(clubs.resolve(slug))) {
            if (dryRun) { return new Session(input,mapping,true).plan().report(); }
            return transactions.execute(tx -> {
                runs.lock(production); census.lock(); catalogs.lock();
                var planned=new Session(input,mapping,false).plan();
                if (!planned.report().hasErrors()) { writer.apply(planned,production); }
                return planned.report();
            });
        }
    }

    public record Change(String entity, String id, Map<String,Object> fields, String email, Set<String> roles) { }
    public record Plan(MigrationReport report, List<Change> changes, int maximumNumber, Instant startedAt, int mappingVersion) { }

    private final class Session {
        final PlayoffInput input; final MappingConfig mapping; final boolean dry;
        final List<MigrationReport.Entry> entries; final List<Change> changes=new ArrayList<>();
        final Map<String,List<Map<String,Object>>> stored=new HashMap<>();
        final Map<String,Change> membersBySource=new HashMap<>();
        final Map<String,Change> persons=new HashMap<>(); final Map<Integer,String> numbers=new HashMap<>();
        final Map<String,String> emails=new HashMap<>(); final Map<String,String> chips=new HashMap<>();
        final Map<String,List<PlayoffInput.Row>> plans=new HashMap<>(), levels=new HashMap<>(), team=new HashMap<>();
        final LocalDate today=clubClock.today(TenantContext.require());
        final ClubConfig config=configs.get(TenantContext.require());
        final ZoneId zone=ZoneId.of(config.club().timeZone());
        int maximum;
        Session(PlayoffInput input, MappingConfig mapping, boolean dry) {
            this.input=input; this.mapping=mapping; this.dry=dry; entries=new ArrayList<>(input.incidents());
        }
        Plan plan() {
            for (String entity:List.of("members","dogs","family_groups")) { stored.put(entity,census.snapshot(entity)); }
            for (String entity:List.of("levels","plans","prices")) { stored.put(entity,catalogs.rows(entity)); }
            for (var old:stored.get("members")) {
                if (old.get("memberNumber") instanceof Number n) { numbers.put(n.intValue(),text(old.get("_id"))); maximum=Math.max(maximum,n.intValue()); }
            }
            for (var old:stored.get("dogs")) { if (old.get("chip")!=null) { chips.put(text(old.get("chip")),text(old.get("_id"))); } }
            index("plans","id",plans); index("levels","id",levels); index("team","number",team);
            var rows=new ArrayList<>(input.files().get("members"));
            rows.sort(Comparator.comparing((PlayoffInput.Row r) -> !"ACTIVE".equals(mapping.statuses().get(norm(r,"status"))))
                    .thenComparing(r -> Optional.ofNullable(date(r.get("joined"))).orElse(LocalDate.MAX)).thenComparingInt(PlayoffInput.Row::row));
            var ids=new HashSet<String>();
            for (var row:rows) {
                if (row.get("id").isEmpty() || !ids.add(row.get("id"))) { error(row,"members","INPUT_SCHEMA_MISMATCH"); continue; }
                Integer number=number(row.get("number")); if (number!=null) { maximum=Math.max(maximum,number); }
                String status=mapping.statuses().get(norm(row,"status"));
                if (status==null) { error(row,"members","INPUT_SCHEMA_MISMATCH"); continue; }
                if (status.equals("SKIP")) { entry(row,"members","SKIPPED",""); continue; }
                LocalDate left=date(row.get("left"));
                // The measured export includes all leave dates in the cutoff calendar year (A24a).
                LocalDate cutoff=today.minusYears(config.get("migration.leftMaxYears",Integer.class)).withDayOfYear(1);
                if (status.equals("LEFT") && (left==null || left.isBefore(cutoff))) { entry(row,"members","SKIPPED",""); continue; }
                if (left!=null && left.isAfter(today)) { warn(row,"members","INPUT_SCHEMA_MISMATCH"); left=null; status="ACTIVE"; }
                var person=member(row,status,left,number);
                if (person!=null) { membersBySource.put(row.get("id"),person); dog(row,person,status); }
            }
            for (String file:List.of("plans","levels")) {
                for (var row:input.files().get(file)) { if (!ids.contains(row.get("id"))) { warn(row,file,"INPUT_SCHEMA_MISMATCH"); } }
            }
            groups();
            return new Plan(new MigrationReport(dry,entries),changes,maximum,clock.instant(),mapping.version());
        }
        void index(String file,String key,Map<String,List<PlayoffInput.Row>> target) {
            for (var row:input.files().get(file)) { target.computeIfAbsent(row.get(key),ignored -> new ArrayList<>()).add(row); }
        }
        Change member(PlayoffInput.Row row,String status,LocalDate left,Integer number) {
            String doc=row.get(norm(row,"hasPassport").equals("s") ? "passport" : "document").replaceAll("[\\s.-]", "").toUpperCase(Locale.ROOT);
            String personKey=doc.isEmpty() ? "row:"+row.get("id") : "doc:"+doc;
            var same=persons.get(personKey);
            if (same!=null) {
                @SuppressWarnings("unchecked") var ids=(List<String>) map(same.fields().get("externalIds")).get("playoff");
                ids.add(row.get("id")); entry(row,"members","SKIPPED",""); return same;
            }
            var existing=stored.get("members").stream().filter(m -> sourceIds(m).contains(row.get("id"))).findFirst().orElse(null);
            String id=existing==null ? UUID.randomUUID().toString() : text(existing.get("_id"));
            if (existing!=null && (existing.get("erasedAt")!=null || "ERASED".equals(existing.get("status")))) { error(row,"members","MEMBER_ERASED"); return null; }
            if (!doc.isEmpty() && stored.get("members").stream().anyMatch(m -> !id.equals(m.get("_id")) && doc.equals(map(m.get("idDocument")).get("number")))) {
                error(row,"members","CLUB_NOT_EMPTY"); return null;
            }
            if (number!=null && numbers.containsKey(number) && !id.equals(numbers.get(number))) { warn(row,"members","NUMBER_CONFLICT"); number=null; }
            if (number!=null) { numbers.put(number,id); }
            String surname=row.get("surname").replaceAll("\\([^()]*\\)","").strip().replaceAll("\\s+"," ");
            String[] surnames=surname.split(" ",2);
            LocalDate joined=date(row.get("joined"));
            if (joined==null || row.get("firstName").isEmpty()) { error(row,"members","INPUT_SCHEMA_MISMATCH"); return null; }
            var fields=object("sourceIds",object("playoffMemberId",row.get("id"),"playoffNumber",row.get("number")),
                    "externalIds",object("playoff",new ArrayList<>(List.of(row.get("id")))), "memberNumber",number,
                    "firstName",row.get("firstName"),"lastName1",surnames[0],"lastName2",surnames.length==2?surnames[1]:null,
                    "status",status,"joinedAt",instant(joined),"leaveDate",left==null?null:left.toString(),
                    "leftAt",left==null?null:instant(left),"leftReason",left==null?null:"MIGRATED");
            if (!doc.isEmpty()) { fields.put("idDocument",object("type",norm(row,"hasPassport").equals("s")?"PASSPORT":doc.matches("[XYZ].*")?"NIE":"DNI","number",doc)); }
            LocalDate birth=date(row.get("birthDate"));
            if (birth!=null && birth.isAfter(today.minusYears(config.get("signup.minAge",Integer.class)))) { warn(row,"members","AGE_SUSPECT"); birth=null; }
            fields.put("birthDate",birth==null?null:birth.toString());
            fields.put("gender",switch(norm(row,"gender")) { case "femení" -> "FEMALE"; case "masculí" -> "MALE"; default -> "OTHER"; });
            var contacts=new ArrayList<Map<String,Object>>();
            for (String key:List.of("email","email2")) { String email=norm(row,key); if (validEmail(email)) { contacts.add(object("email",email,"bounced",false)); } }
            fields.put("contactEmails",contacts);
            var phones=new ArrayList<Map<String,Object>>();
            for (String key:List.of("phone","phone2")) {
                if (!row.get(key).isEmpty()) { try { phones.add(countries.phone(null,row.get(key),null)); } catch (ApiException invalid) { warn(row,"members","INVALID_PHONE"); } }
            }
            if (phones.isEmpty()) { warn(row,"members","INVALID_PHONE"); } fields.put("phones",phones);
            fields.put("address",object("street",row.get("street"),"postalCode",row.get("postalCode"),"city",row.get("city"),
                    "province",norm(row,"province"),"country",norm(row,"country").equals("espanya")?"ES":row.get("country")));
            if (row.get("street").isEmpty() || row.get("postalCode").isEmpty()) { warn(row,"members","INPUT_SCHEMA_MISMATCH"); }
            fields.put("internalNotes",row.get("notes"));
            if (existing==null) {
                fields.put("consents",object("privacy",object("version","LEGACY","acceptedAt",instant(joined),"source","MIGRATED")));
                fields.put("notificationPreferences",object("essentialOnly",norm(row,"notifications").equals("no")));
            }
            if (norm(row,"status").equals("bloqueado")) { fields.put("bookingBlock",object("active",true,"reason","MIGRATED","setAt",clock.instant())); }
            fields.put("paymentMethod",payment(row));
            var roles=new HashSet<>(Set.of("MEMBER"));
            for (var role:team.getOrDefault(row.get("number"),List.of())) {
                String value=role.get("role").toUpperCase(Locale.ROOT);
                if (Set.of("MEMBER","INSTRUCTOR","ADMIN").contains(value)) { roles.add(value); } else { warn(role,"accounts","INPUT_SCHEMA_MISMATCH"); }
            }
            linkPlan(row,fields,roles);
            String email=norm(row,"email");
            if (!validEmail(email)) { warn(row,"accounts","INPUT_SCHEMA_MISMATCH"); email=null; }
            else {
                var preview=identity.preview(email);
                if (emails.containsKey(email) || preview!=null && (preview.memberId()!=null && !preview.memberId().equals(id) || !preview.active())) {
                    warn(row,"accounts","EMAIL_SHARED"); email=null;
                } else { emails.put(email,id); entry(row,"accounts",preview==null?"CREATED":"UPDATED",""); }
            }
            if (email==null) { entry(row,"accounts","SKIPPED",""); }
            var change=new Change("members",id,fields,email,roles); persons.put(personKey,change); changes.add(change);
            entry(row,"members",existing==null?"CREATED":"UPDATED",""); return change;
        }
        Map<String,Object> payment(PlayoffInput.Row row) {
            return switch(norm(row,"payment")) {
                case "domiciliació bancària" -> {
                    String iban=row.get("iban").replaceAll("\\s","").toUpperCase(Locale.ROOT);
                    if (iban.isEmpty()) { warn(row,"members","NO_BANK_ACCOUNT"); }
                    else if (!PlayoffAnonymizer.validIban(iban)) { warn(row,"members","IBAN_INVALID"); iban=""; }
                    yield object("type","SEPA_DD","iban",iban.isEmpty()?null:iban,"holderName",row.get("holder").isEmpty()?row.get("firstName")+" "+row.get("surname").replaceAll("\\([^()]*\\)","").strip():row.get("holder"));
                }
                case "paga en efectiu" -> object("type","MANUAL","subtype","CASH");
                case "transferència" -> object("type","MANUAL","subtype","TRANSFER");
                default -> { warn(row,"members","CARD_NOT_MIGRATED"); yield object("type","MANUAL"); }
            };
        }
        void linkPlan(PlayoffInput.Row row,Map<String,Object> fields,Set<String> roles) {
            var matches=plans.getOrDefault(row.get("id"),List.of());
            String key=matches.size()==1?norm(matches.getFirst(),"plan"):"";
            if (mapping.instructorPlans().contains(key)) { roles.add("INSTRUCTOR"); }
            if (mapping.familyPlans().contains(key)) { warn(row,"familyGroups","EMAIL_SHARED"); }
            String code=mapping.plans().get(key);
            var plan=stored.get("plans").stream().filter(p -> Objects.equals(code,p.get("code")) && Boolean.TRUE.equals(p.get("active"))).findFirst().orElse(null);
            fields.put("planId",null); fields.put("priceId",null); fields.put("nextInvoiceDate",null);
            if (plan==null) { warn(row,"members",mapping.unresolvedPlans().contains(key)?"PLAN_UNMAPPED":"LEGACY_PLAN"); return; }
            fields.put("planId",plan.get("_id"));
            boolean monthly="MONTHLY".equals(plan.get("type"));
            String concept=monthly?"MONTHLY_FEE":"PACK".equals(plan.get("type"))?"PACK":"SINGLE_CLASS";
            var price=stored.get("prices").stream().filter(p -> plan.get("_id").equals(p.get("planId")) && concept.equals(p.get("concept")))
                    .filter(p -> date(text(p.get("validFrom")))!=null && !date(text(p.get("validFrom"))).isAfter(today)
                            && (p.get("validTo")==null || !date(text(p.get("validTo"))).isBefore(today)))
                    .max(Comparator.comparing(p -> text(p.get("validFrom")))).orElse(null);
            if (price==null) { warn(row,"members","LEGACY_PLAN"); } else { fields.put("priceId",price.get("_id")); }
            if (monthly && "ACTIVE".equals(fields.get("status"))) { fields.put("nextInvoiceDate",today.plusMonths(1).withDayOfMonth(1).toString()); }
        }
        void dog(PlayoffInput.Row row,Change person,String status) {
            var existing=stored.get("dogs").stream().filter(d -> row.get("id").equals(map(d.get("sourceIds")).get("playoffMemberId"))).findFirst().orElse(null);
            String id=existing==null?UUID.randomUUID().toString():text(existing.get("_id"));
            String name=row.get("dogName");
            if (name.isEmpty()) {
                var matcher=java.util.regex.Pattern.compile("\\(([^()]*)\\)").matcher(row.get("surname"));
                if (matcher.find() && !matcher.group(1).isBlank()) { name=matcher.group(1).strip(); warn(row,"dogs","DOG_NAME_FROM_SURNAME"); }
                else { name="Gos de "+row.get("firstName"); warn(row,"dogs","DOG_INFERRED"); }
            }
            String chip=row.get("chip");
            if (chip.isEmpty()) { warn(row,"dogs","CHIP_MISSING"); }
            else if (chips.containsKey(chip) && !id.equals(chips.get(chip))) { error(row,"dogs","CHIP_EXISTS"); return; }
            else { chips.put(chip,id); }
            var birth=date(row.get("dogBirth"));
            var fields=object("sourceIds",object("playoffMemberId",row.get("id"),"playoffDogIndex",0),"externalIds",object("playoff",row.get("id")),
                    "memberId",person.id(),"name",name,"breed",row.get("breed"),"sex",norm(row,"dogSex").equals("femella")?"FEMALE":"MALE",
                    "chip",chip.isEmpty()?null:chip,"birthDate",birth==null?null:birth.toString(),"handlerName",row.get("handler"),
                    "status",status.equals("LEFT")?"INACTIVE":"ACTIVE");
            if (existing==null) { fields.put("registeredAt",person.fields().get("joinedAt")); }
            if (!row.get("objectives").isEmpty()) { fields.put("instructorNote",object("text",row.get("objectives"),"updatedAt",clock.instant())); }
            if (!row.get("photo").isEmpty()) { warn(row,"dogs","INPUT_SCHEMA_MISMATCH"); }
            var licenses=new ArrayList<Map<String,Object>>();
            if (!row.get("rsce").isEmpty()) { licenses.add(object("organisation","RSCE","number",row.get("rsce"),"category",row.get("category"),"grade",row.get("grade"))); }
            if (!row.get("fcag").isEmpty()) { licenses.add(object("organisation","FCAG","number",row.get("fcag"),"division",row.get("division"))); }
            fields.put("licenses",licenses);
            var assigned=levels.getOrDefault(row.get("id"),List.of()); var actual=new ArrayList<PlayoffInput.Row>();
            for (var level:assigned) {
                String key=norm(level,"level");
                if (mapping.levelFlags().containsKey(key)) { if ("THERAPY".equals(mapping.levelFlags().get(key))) { warn(level,"dogs","PLAN_UNMAPPED"); } }
                else { actual.add(level); }
            }
            fields.put("levelId",null);
            if (actual.size()==1) {
                var source=actual.getFirst(); String key=norm(source,"level"); String code=mapping.levels().get(key);
                var level=stored.get("levels").stream().filter(l -> Objects.equals(code,l.get("code")) && Boolean.TRUE.equals(l.get("active"))).findFirst().orElse(null);
                if (level==null) { warn(source,"dogs",mapping.unresolvedLevels().contains(key)?"LEVEL_PENDING":"INPUT_SCHEMA_MISMATCH"); }
                else {
                    fields.put("levelId",level.get("_id")); var day=date(source.get("assigned"));
                    if (day==null) { warn(source,"dogs","INPUT_SCHEMA_MISMATCH"); }
                    else { fields.put("levelAssignedAt",instant(day));
                        if (existing==null) { fields.put("levelHistory",List.of(object("levelId",level.get("_id"),"assignedAt",instant(day),"reason","MIGRATED"))); }
                    }
                }
            } else { warn(row,"dogs","INPUT_SCHEMA_MISMATCH"); }
            changes.add(new Change("dogs",id,fields,null,Set.of())); entry(row,"dogs",existing==null?"CREATED":"UPDATED","");
        }
        void groups() {
            var groups=new LinkedHashMap<String,List<PlayoffInput.Row>>(); index("groups","groupId",groups); var grouped=new HashSet<String>();
            for (var item:groups.entrySet()) {
                var rows=item.getValue(); var row=rows.getFirst(); var holder=membersBySource.get(row.get("holderId")); var ids=new LinkedHashSet<String>();
                boolean valid=holder!=null;
                for (var link:rows) {
                    var member=membersBySource.get(link.get("id"));
                    if (member==null || !link.get("holderId").equals(row.get("holderId"))) { valid=false; }
                    else { ids.add(member.id()); }
                }
                if (!valid || ids.size()<2 || !ids.contains(holder.id()) || ids.stream().anyMatch(grouped::contains)) { error(row,"familyGroups","INPUT_SCHEMA_MISMATCH"); continue; }
                var existing=stored.get("family_groups").stream().filter(g -> item.getKey().equals(map(g.get("sourceIds")).get("playoffGroupId"))).findFirst().orElse(null);
                String id=existing==null?UUID.randomUUID().toString():text(existing.get("_id"));
                if (stored.get("family_groups").stream().anyMatch(g -> !id.equals(g.get("_id")) && g.get("memberIds") instanceof List<?> old && old.stream().anyMatch(ids::contains))) {
                    error(row,"familyGroups","CLUB_NOT_EMPTY"); continue;
                }
                grouped.addAll(ids);
                for (var member:persons.values()) { if (ids.contains(member.id())) { member.fields().put("familyGroupId",id); } }
                changes.add(new Change("family_groups",id,object("sourceIds",object("playoffGroupId",item.getKey()),"externalIds",object("playoff",item.getKey()),
                        "holderMemberId",holder.id(),"memberIds",new ArrayList<>(ids),"status","ACTIVE"),null,Set.of()));
                entry(row,"familyGroups",existing==null?"CREATED":"UPDATED","");
            }
        }
        Instant instant(LocalDate day) { return day.atStartOfDay(zone).toInstant(); }
        void warn(PlayoffInput.Row row,String entity,String code) { entry(row,entity,"WARNING",code); }
        void error(PlayoffInput.Row row,String entity,String code) { entry(row,entity,"ERROR",code); }
        void entry(PlayoffInput.Row row,String entity,String outcome,String code) { entries.add(new MigrationReport.Entry(row.file(),row.row(),entity,outcome,code)); }
    }
    static String norm(PlayoffInput.Row row,String field) { return MappingConfig.normalize(row.get(field)); }
    static String text(Object value) { return value==null?"":value.toString(); }
    static LocalDate date(String value) {
        for (var pattern:List.of(DateTimeFormatter.ISO_LOCAL_DATE,DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT))) {
            try { return LocalDate.parse(value,pattern); } catch (java.time.format.DateTimeParseException invalid) { /* Try the other documented export format. */ }
        }
        return null;
    }
    static Integer number(String value) { try { int result=Integer.parseInt(value); return result>0?result:null; } catch (NumberFormatException invalid) { return null; } }
    static boolean validEmail(String value) { return org.apache.commons.validator.routines.EmailValidator.getInstance().isValid(value); }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) { return value instanceof Map<?,?> ? (Map<String,Object>)value : Map.of(); }
    static List<?> sourceIds(Map<String,Object> value) { return map(value.get("externalIds")).get("playoff") instanceof List<?> ids?ids:List.of(); }
    static Map<String,Object> object(Object... pairs) {
        var result=new LinkedHashMap<String,Object>(); for (int i=0;i<pairs.length;i+=2) { result.put((String)pairs[i],pairs[i+1]); } return result;
    }
}
