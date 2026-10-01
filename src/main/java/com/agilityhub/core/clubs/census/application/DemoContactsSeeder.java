package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.shared.application.DemoSeedStep;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/**
 * E7-T04 step 4, the census half of the demo seed's `messaging` section: the contact data of its preference profiles (a
 * second phone, a second e-mail, the address that will be marked as bounced), through the admin's D10 edit (S03
 * `MemberService.patch`, at most two of each). The S11 half — the bounce mark, the preferences, the push device and the
 * `CUSTOM` templates — is `clubs.messaging.application.DemoMessagingSeeder` (order 51), which never reads the census.
 */
@Service
public class DemoContactsSeeder implements DemoSeedStep {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(int member, List<String> extraPhones, List<String> extraEmails, List<String> bouncedEmails) {
        public Profile {
            extraPhones = extraPhones == null ? List.of() : List.copyOf(extraPhones); extraEmails = extraEmails == null ? List.of() : List.copyOf(extraEmails);
            bouncedEmails = bouncedEmails == null ? List.of() : List.copyOf(bouncedEmails);
        }
        boolean contacts() { return !extraPhones.isEmpty() || !extraEmails.isEmpty() || !bouncedEmails.isEmpty(); }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(List<Profile> profiles) {
        public Spec { profiles = profiles == null ? List.of() : List.copyOf(profiles); }
    }
    static final String SECTION = "messaging";
    private final CensusAccess census; private final MemberService members; private final ObjectMapper mapper;

    public DemoContactsSeeder(CensusAccess census, MemberService members, ObjectMapper mapper) { this.census = census; this.members = members; this.mapper = mapper; }
    @Override public int order() { return 50; }

    @Override public Map<String, Integer> apply(Input input) {
        var counts = new LinkedHashMap<String, Integer>(); counts.put("messagingContacts", 0);
        var section = input.specification().get(SECTION);
        if (section == null) { return counts; }
        for (var profile : mapper.convertValue(section, Spec.class).profiles()) {
            if (!profile.contacts()) { continue; }
            contacts(input.member(profile.member()), profile); counts.merge("messagingContacts", 1, Integer::sum);
        }
        return counts;
    }

    /** The member's contact rows plus the profile's extra ones (`+34600000901` → prefix `+34`, number `600000901`). */
    private void contacts(String memberId, Profile profile) {
        var member = census.members.require(memberId);
        var emails = new ArrayList<Map<String, Object>>();
        rows(member.contactEmails).forEach(row -> emails.add(Map.of("email", string(row.get("email")))));
        for (String address : concat(profile.extraEmails(), profile.bouncedEmails())) {
            if (emails.stream().noneMatch(e -> address.equalsIgnoreCase(Objects.toString(e.get("email"))))) { emails.add(Map.of("email", address)); }
        }
        var phones = new ArrayList<Map<String, Object>>();
        rows(member.phones).forEach(row -> phones.add(Map.of("prefix", string(row.get("prefix")), "number", string(row.get("number")))));
        profile.extraPhones().forEach(phone -> phones.add(Map.of("prefix", phone.substring(0, 3), "number", phone.substring(3))));
        var patch = new LinkedHashMap<String, Object>();
        patch.put("contactEmails", emails); patch.put("phones", phones); patch.put("version", member.version());
        members.patch(memberId, patch, false);
    }
    private static List<String> concat(List<String> first, List<String> second) { var all = new ArrayList<>(first); all.addAll(second); return all; }
}
