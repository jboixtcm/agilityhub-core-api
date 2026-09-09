package com.agilityhub.core.clubs.census.domain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Pure fictional data generator. Club-specific values and counts belong to the seed file. */
public final class DemoDataset {
    private DemoDataset() { }
    public record Spec(int activeMembers, int pendingMembers, int inactiveMembers, int leftMembers,
            Map<String, Integer> levelDogs, List<String> firstNames, String surnamePrefix, List<String> dogNames,
            List<String> accountEmails, List<String> planCodes, String familyPlanCode, int familyGroups,
            List<Integer> instructors, List<Integer> administrators, int receivedDocuments, LocalDate referenceDate) {
        public Spec {
            if (activeMembers < 5 || activeMembers > 1000 || pendingMembers < 0 || inactiveMembers < 0 || leftMembers < 0
                    || pendingMembers + inactiveMembers + leftMembers > 1000 || levelDogs == null || levelDogs.isEmpty()
                    || levelDogs.values().stream().anyMatch(n -> n < 0 || n > 1000)
                    || levelDogs.values().stream().mapToInt(Integer::intValue).sum() < activeMembers
                    || firstNames == null || firstNames.isEmpty() || surnamePrefix == null || surnamePrefix.isBlank()
                    || dogNames == null || dogNames.isEmpty() || planCodes == null || planCodes.isEmpty()
                    || accountEmails == null || accountEmails.size() > activeMembers
                    || accountEmails.stream().anyMatch(email -> !email.matches("[^@\\s]+@example\\.test"))
                    || new HashSet<>(accountEmails).size() != accountEmails.size()
                    || familyGroups < 0 || familyGroups * 2 > activeMembers || receivedDocuments < 0
                    || receivedDocuments > levelDogs.values().stream().mapToInt(Integer::intValue).sum()
                    || instructors == null || administrators == null || referenceDate == null) { throw new IllegalArgumentException("Invalid demo seed specification"); }
            for (var team : List.of(instructors, administrators)) {
                if (new HashSet<>(team).size() != team.size() || team.stream().anyMatch(i -> i < 0 || i >= activeMembers)) {
                    throw new IllegalArgumentException("Invalid demo team member ordinal");
                }
            }
            levelDogs = Collections.unmodifiableMap(new LinkedHashMap<>(levelDogs));
        }
    }
    public record MemberRow(String id, int number, String firstName, String surname, String email, String status, String planCode, String iban) { }
    public record DogRow(String id, String memberId, String name, String levelCode, LocalDate birthDate) { }
    public record Data(List<MemberRow> members, List<DogRow> dogs) { }
    public static String id(String club, String kind, int ordinal) {
        return UUID.nameUUIDFromBytes((club + ":demo:" + kind + ":" + ordinal).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static Data generate(Spec spec, long seed, String club) {
        var random = new Random(seed); var members = new ArrayList<MemberRow>(); var dogs = new ArrayList<DogRow>();
        var statuses = new LinkedHashMap<String, Integer>(); statuses.put("ACTIVE", spec.activeMembers());
        statuses.put("PENDING", spec.pendingMembers()); statuses.put("INACTIVE", spec.inactiveMembers()); statuses.put("LEFT", spec.leftMembers());
        for (var status : statuses.entrySet()) { for (int n = 0; n < status.getValue(); n++) {
            int i = members.size();
            String email = i < spec.accountEmails().size() ? spec.accountEmails().get(i) : "demo." + club + "." + (i + 1) + "@example.test";
            members.add(new MemberRow(id(club, "member", i), i + 1, spec.firstNames().get(random.nextInt(spec.firstNames().size())),
                    spec.surnamePrefix() + String.format(Locale.ROOT, "%03d", i + 1), email, status.getKey(),
                    i < spec.familyGroups() * 2 ? spec.familyPlanCode() : spec.planCodes().get(i % spec.planCodes().size()), iban(i + 1)));
        } }
        for (var level : spec.levelDogs().entrySet()) { for (int n = 0; n < level.getValue(); n++) {
            int i = dogs.size();
            dogs.add(new DogRow(id(club, "dog", i), members.get(i % spec.activeMembers()).id(),
                    spec.dogNames().get(random.nextInt(spec.dogNames().size())) + " " + (i + 1), level.getKey(),
                    spec.referenceDate().minusDays(180 + random.nextInt(2000))));
        } }
        return new Data(List.copyOf(members), List.copyOf(dogs));
    }
    /** Reserved fictional bank/branch 0000; valid domestic check digits and IBAN mod-97. */
    public static String iban(int ordinal) {
        String account = String.format(Locale.ROOT, "%010d", ordinal);
        int[] weights = {1,2,4,8,5,10,9,7,3,6}; int sum = 0;
        for (int i = 0; i < account.length(); i++) { sum += (account.charAt(i) - '0') * weights[i]; }
        int check = 11 - sum % 11; check = check == 11 ? 0 : check == 10 ? 1 : check;
        String bban = "000000000" + check + account;
        int mod = new BigInteger(bban + "142800").mod(BigInteger.valueOf(97)).intValue();
        return "ES" + String.format(Locale.ROOT, "%02d", 98 - mod) + bban;
    }
}
