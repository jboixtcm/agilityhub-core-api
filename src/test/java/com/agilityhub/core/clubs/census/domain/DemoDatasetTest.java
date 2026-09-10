package com.agilityhub.core.clubs.census.domain;

import com.agilityhub.core.clubs.census.support.DemoFixtures;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DemoDatasetTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @Test void T_03_42_demoFixtureIsDeterministicFictionalAndMatchesD1D3() throws Exception {
        var spec = DemoFixtures.spec(mapper, false); var data = DemoDataset.generate(spec, 42, "example");
        assertThat(data).isEqualTo(DemoDataset.generate(spec, 42, "example"));
        assertThat(data).isNotEqualTo(DemoDataset.generate(spec, 43, "example"));
        assertThat(data.members()).hasSize(194); assertThat(data.dogs()).hasSize(242);
        assertThat(data.members().stream().filter(m -> m.status().equals("ACTIVE"))).hasSize(184);
        assertThat(data.members().stream().filter(m -> m.status().equals("PENDING"))).hasSize(3);
        for (var entry : spec.levelDogs().entrySet()) { assertThat(data.dogs().stream().filter(d -> d.levelCode().equals(entry.getKey()))).hasSize(entry.getValue()); }
        assertThat(data.members()).allSatisfy(member -> {
            assertThat(member.email()).endsWith("@example.test"); assertThat(member.surname()).startsWith("Fictici");
            assertThat(member.iban()).matches("ES[0-9]{22}"); assertThat(member.iban().substring(4, 12)).isEqualTo("00000000");
            assertThat(new BigInteger(member.iban().substring(4) + "1428" + member.iban().substring(2, 4)).mod(BigInteger.valueOf(97))).isEqualTo(BigInteger.ONE);
        });
        assertThat(data.members()).extracting(DemoDataset.MemberRow::email).doesNotHaveDuplicates();
        assertThat(data.dogs()).allSatisfy(dog -> assertThat(data.members()).anyMatch(m -> m.id().equals(dog.memberId()) && m.status().equals("ACTIVE")));
        assertThat(DemoDataset.id("another", "dog", 0)).isNotEqualTo(data.dogs().getFirst().id());
        var small = DemoDataset.generate(DemoFixtures.spec(mapper, true), 42, "example");
        assertThat(small.members()).hasSize(13); assertThat(small.dogs()).hasSize(16);
    }
    @Test void T_03_42_invalidSeedCountsIdentitiesAndReferencesAreRejected() throws Exception {
        ObjectNode base = mapper.valueToTree(DemoFixtures.spec(mapper, true));
        var invalid = new LinkedHashMap<String, List<Object>>();
        invalid.put("activeMembers", List.of(0, 1001)); invalid.put("pendingMembers", List.of(-1, 1001));
        invalid.put("inactiveMembers", List.of(-1)); invalid.put("leftMembers", List.of(-1));
        invalid.put("levelDogs", Arrays.asList(null, Map.of(), Map.of("A", -1), Map.of("A", 1001), Map.of("A", 1)));
        invalid.put("firstNames", Arrays.asList(null, List.of())); invalid.put("surnamePrefix", Arrays.asList(null, ""));
        invalid.put("dogNames", Arrays.asList(null, List.of())); invalid.put("planCodes", Arrays.asList(null, List.of()));
        invalid.put("accountEmails", Arrays.asList(null, Collections.nCopies(9, "a@example.test"), List.of("real@invalid.test"), List.of("a@example.test", "a@example.test")));
        invalid.put("familyGroups", List.of(-1, 9)); invalid.put("receivedDocuments", List.of(-1, 1000));
        invalid.put("instructors", Arrays.asList(null, List.of(-1), List.of(8), List.of(1, 1)));
        invalid.put("administrators", Arrays.asList(null, List.of(8))); invalid.put("referenceDate", Arrays.asList((Object) null));
        invalid.put("pendingSignups", Arrays.asList(null, List.of()));
        invalid.forEach((field, values) -> values.forEach(value -> {
            var input = base.deepCopy(); input.set(field, mapper.valueToTree(value));
            assertThatThrownBy(() -> mapper.convertValue(input, DemoDataset.Spec.class)).as(field + "=" + value).isInstanceOf(IllegalArgumentException.class);
        }));
    }
    @Test void T_14_11_pendingSignupFixturesRejectInvalidFields() throws Exception {
        var original = mapper.valueToTree(DemoFixtures.spec(mapper, true).pendingSignups().getFirst());
        var invalid = new LinkedHashMap<String, List<Object>>();
        invalid.put("planCode", Arrays.asList(null, "")); invalid.put("daysAgo", List.of(-1, 366));
        invalid.put("dogName", Arrays.asList(null, "")); invalid.put("chip", Arrays.asList(null, "invalid"));
        invalid.forEach((field, values) -> values.forEach(value -> {
            ObjectNode input = original.deepCopy(); input.set(field, mapper.valueToTree(value));
            assertThatThrownBy(() -> mapper.convertValue(input, DemoDataset.PendingSignup.class)).isInstanceOf(IllegalArgumentException.class);
        }));
    }
}
