package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Selected writes over the complete, strictly validated private census plan. */
@Service
public class MigrationCensusSteps {
    private static final Set<String> PERSON = Set.of("sourceIds","externalIds","firstName","lastName1","lastName2",
            "joinedAt","contactEmails","phones","address","gender","internalNotes","notificationPreferences",
            "idDocument","memberNumber","birthDate","consents");
    private static final Set<String> PAYMENT = Set.of("paymentMethod","planId","priceId","nextInvoiceDate");
    private static final Set<String> STATE = Set.of("status","leaveDate","leftAt","leftReason","bookingBlock","leaveRequestId");
    private static final Set<String> DOG_STATE = Set.of("status","deactivatedAt","deactivationReason");
    private final CensusMigrationService census;
    public MigrationCensusSteps(CensusMigrationService census) { this.census=census; }

    public record Selection(Map<Integer,List<PlayoffPlanner.Change>> changes,List<PlayoffPlanner.Identity> identities,
            int maximumNumber,List<MigrationReport.Entry> rows,PlayoffPlanner.Plan preview) {
        public Selection {
            var copy=new TreeMap<Integer,List<PlayoffPlanner.Change>>(); changes.forEach((step,values)->copy.put(step,List.copyOf(values)));
            changes=Collections.unmodifiableMap(copy); identities=List.copyOf(identities); rows=List.copyOf(rows);
        }
        public List<PlayoffPlanner.Change> changes(int step) { return changes.getOrDefault(step,List.of()); }
    }

    public Selection select(PlayoffPlanner.Plan full,MigrationSteps steps) {
        var members=snapshot("members"); var dogs=snapshot("dogs"); var groups=snapshot("family_groups");
        var plannedMembers=new HashMap<String,PlayoffPlanner.Change>();
        full.changes().stream().filter(change->change.entity().equals("members")).forEach(change->plannedMembers.put(change.id(),change));
        var staged=new TreeMap<Integer,List<PlayoffPlanner.Change>>();
        for (var change:full.changes()) {
            switch(change.entity()) {
                case "members" -> {
                    boolean existing=imported(members.get(change.id()));
                    if (steps.includes(3)) {
                        var fields=fields(change.fields(),PERSON);
                        // A partial person has no access or effective business state until step 6.
                        if (!existing) {
                            fields.put("status","PENDING");
                            Object groupId=change.fields().get("familyGroupId");
                            var group=groups.get(Objects.toString(groupId,null));
                            boolean resumedGroup=imported(group) && group.get("memberIds") instanceof List<?> ids && ids.contains(change.id());
                            if (groupId!=null && (steps.includes(2) || resumedGroup)) {
                                fields.put("familyGroupId",change.fields().get("familyGroupId"));
                            }
                        }
                        add(staged,3,change,fields);
                    }
                    if (steps.includes(2) && existing && change.fields().containsKey("familyGroupId")) {
                        add(staged,2,change,fields(change.fields(),Set.of("familyGroupId")));
                    }
                    if (steps.includes(5)) {
                        requireMember(change.id(),members,plannedMembers,steps,5);
                        add(staged,5,change,fields(change.fields(),PAYMENT));
                    }
                    if (steps.includes(6)) {
                        requireMember(change.id(),members,plannedMembers,steps,6);
                        add(staged,6,change,fields(change.fields(),STATE));
                    }
                }
                case "dogs" -> {
                    if (steps.includes(4)) {
                        requireMember(Objects.toString(change.fields().get("memberId"),null),members,plannedMembers,steps,4);
                        var fields=new LinkedHashMap<>(change.fields()); DOG_STATE.forEach(fields::remove);
                        if (!imported(dogs.get(change.id()))) { fields.put("status","INACTIVE"); }
                        add(staged,4,change,fields);
                    }
                    if (steps.includes(6)) {
                        if (!steps.includes(4) && !imported(dogs.get(change.id()))) { throw dependency(6,4); }
                        add(staged,6,change,fields(change.fields(),DOG_STATE));
                    }
                }
                case "leaves" -> {
                    if (steps.includes(6)) {
                        requireMember(Objects.toString(change.fields().get("memberId"),null),members,plannedMembers,steps,6);
                        add(staged,6,change,change.fields());
                    }
                }
                case "family_groups" -> {
                    if (steps.includes(2)) {
                        Object references=change.fields().get("memberIds");
                        if (!(references instanceof List<?> ids)) { throw dependency(2,3); }
                        for (Object id:ids) { requireMember(Objects.toString(id,null),members,plannedMembers,steps,2); }
                        requireMember(Objects.toString(change.fields().get("holderMemberId"),null),members,plannedMembers,steps,2);
                        add(staged,2,change,change.fields());
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported census migration entity");
            }
        }
        // Group documents precede existing-member backlinks. New-member backlinks belong to that member's step-3 insert.
        if (staged.containsKey(2)) { staged.get(2).sort(Comparator.comparing(change->!change.entity().equals("family_groups"))); }
        var effective=effective(staged,members,dogs);
        var identities=new ArrayList<PlayoffPlanner.Identity>();
        for (var identity:steps.includes(9) ? full.identities() : List.<PlayoffPlanner.Identity>of()) {
            requireMember(identity.memberId(),members,plannedMembers,steps,9);
            var state=effective.stream().filter(change->change.entity().equals("members") && change.id().equals(identity.memberId()))
                    .map(PlayoffPlanner.Change::fields).findFirst().orElse(members.get(identity.memberId()));
            if (state==null || !"ACTIVE".equals(state.get("status"))) { throw dependency(9,6); }
            var planned=plannedMembers.get(identity.memberId());
            if (!steps.includes(6) && (planned==null || STATE.stream().anyMatch(field->planned.fields().containsKey(field)
                    && !Objects.equals(planned.fields().get(field),state.get(field))))) { throw dependency(9,6); }
            if (!(state.get("contactEmails") instanceof List<?> contacts) || contacts.isEmpty()
                    || !(contacts.getFirst() instanceof Map<?,?> contact)
                    || !identity.email().equalsIgnoreCase(Objects.toString(contact.get("email"),"").strip())) {
                throw dependency(9,3);
            }
            String name=String.join(" ",List.of("firstName","lastName1","lastName2").stream()
                    .map(field->Objects.toString(state.get(field),"").strip()).filter(value->!value.isEmpty()).toList());
            identities.add(new PlayoffPlanner.Identity(identity.memberId(),identity.email(),name,identity.locale(),identity.active(),identity.roles()));
        }
        var rows=selectedRows(full,staged,steps);
        int maximum=steps.includes(3) ? full.maximumNumber() : 0;
        return new Selection(staged,identities,maximum,rows,new PlayoffPlanner.Plan(effective,identities,maximum,rows));
    }

    private Map<String,Map<String,Object>> snapshot(String collection) {
        var result=new LinkedHashMap<String,Map<String,Object>>();
        census.snapshot(collection).forEach(row->result.put(Objects.toString(row.get("_id")),row)); return result;
    }
    private static boolean imported(Map<String,Object> value) {
        return value!=null && value.get("sourceIds")!=null && value.get("erasedAt")==null && !"ERASED".equals(value.get("status"));
    }
    private static void requireMember(String id,Map<String,Map<String,Object>> stored,
            Map<String,PlayoffPlanner.Change> planned,MigrationSteps steps,int step) {
        if (!imported(stored.get(id)) && !(steps.includes(3) && planned.containsKey(id))) { throw dependency(step,3); }
    }
    private static ApiException dependency(int step,int required) {
        return new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH,Map.of("field","steps","step",step,"requiredStep",required));
    }
    private static LinkedHashMap<String,Object> fields(Map<String,Object> source,Set<String> keys) {
        var result=new LinkedHashMap<String,Object>(); source.forEach((key,value)->{ if (keys.contains(key)) { result.put(key,value); } }); return result;
    }
    private static void add(Map<Integer,List<PlayoffPlanner.Change>> staged,int step,PlayoffPlanner.Change original,Map<String,Object> fields) {
        if (!fields.isEmpty()) { staged.computeIfAbsent(step,key->new ArrayList<>()).add(new PlayoffPlanner.Change(original.entity(),original.id(),new LinkedHashMap<>(fields),original.source())); }
    }
    private static List<PlayoffPlanner.Change> effective(Map<Integer,List<PlayoffPlanner.Change>> staged,
            Map<String,Map<String,Object>> members,Map<String,Map<String,Object>> dogs) {
        var merged=new LinkedHashMap<String,PlayoffPlanner.Change>();
        staged.values().stream().flatMap(Collection::stream).forEach(change->{
            String key=change.entity()+":"+change.id(); var previous=merged.get(key);
            Map<String,Object> stored=change.entity().equals("members") ? members.get(change.id()) : change.entity().equals("dogs") ? dogs.get(change.id()) : null;
            var fields=new LinkedHashMap<>(previous!=null ? previous.fields() : stored!=null ? stored : Map.<String,Object>of());
            fields.putAll(change.fields()); merged.put(key,new PlayoffPlanner.Change(change.entity(),change.id(),fields,change.source()));
        });
        return List.copyOf(merged.values());
    }
    private static List<MigrationReport.Entry> selectedRows(PlayoffPlanner.Plan full,Map<Integer,List<PlayoffPlanner.Change>> staged,MigrationSteps steps) {
        var sources=new HashMap<String,Set<String>>();
        staged.values().stream().flatMap(Collection::stream).forEach(change->sources.computeIfAbsent(change.entity(),key->new HashSet<>())
                .add(change.source().file()+":"+change.source().row()));
        return full.rows().stream().filter(row->{
            if (Set.of("ERROR","WARNING").contains(row.outcome())) { return true; }
            if (row.entity().equals("accounts")) { return steps.includes(9); }
            if (row.entity().equals("familyGroups") && row.outcome().equals("PROPOSED")) { return steps.includes(2); }
            if (row.outcome().equals("SKIPPED")) {
                return switch(row.entity()) {
                    case "members" -> steps.includes(3);
                    case "dogs" -> steps.includes(4);
                    case "leaves" -> steps.includes(6);
                    case "familyGroups" -> steps.includes(2);
                    default -> false;
                };
            }
            String entity=row.entity().equals("familyGroups") ? "family_groups" : row.entity();
            return sources.getOrDefault(entity,Set.of()).contains(row.file()+":"+row.row());
        }).toList();
    }
}
