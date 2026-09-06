package com.agilityhub.core.platform.domain.audit;

import com.agilityhub.core.platform.application.audit.AuditChange;
import com.agilityhub.core.shared.domain.audit.AuditField;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static com.agilityhub.core.shared.domain.audit.Sensitive.Strategy.*;

class AuditDiffTest {
    record Payment(@AuditField @Sensitive String iban) { }
    record Phone(@AuditField String number) { }
    record Member(@AuditField Payment paymentMethod, @AuditField List<Phone> phones,
                  @AuditField @Sensitive(HIDE) String passwordHash, String ignoredSecret) { }
    record Secrets(@AuditField @Sensitive(MASK_ID_DOCUMENT) String document,
                   @AuditField @Sensitive(HIDE) Object token) { }

    @Test void T_14_07_masksIbanAndKeepsChangedPhoneArrayInClear() {
        Member before = member("ES0000000000000000002231", "222", "hash");
        Member after = member("ES0000000000000000008867", "333", "hash");
        assertThat(diff(before, after)).containsExactly(
                new AuditChange("paymentMethod.iban", "···· ···· ···· ···· 2231", "···· ···· ···· ···· 8867"),
                new AuditChange("phones", List.of(Map.of("number", "111"), Map.of("number", "222")),
                        List.of(Map.of("number", "111"), Map.of("number", "333"))));
        assertThat(diff(before, after).toString()).doesNotContain("ES000", "ignored-secret", "hash");
    }

    @Test void T_14_07_detectsChangesEvenWhenMaskedValuesAreIdentical() {
        var changes = diff(member("ES0000002231", "222", "hash-a"), member("FR1111112231", "222", "hash-b"));
        assertThat(changes).containsExactly(
                new AuditChange("paymentMethod.iban", "···· ···· ···· ···· 2231", "···· ···· ···· ···· 2231"),
                new AuditChange("passwordHash", "[ocult]", "[ocult]"));
        assertThat(diff(member(null, "222", null), member(null, "222", null))).isEmpty();
        assertThat(diff(null, null)).isEmpty();
    }

    @Test void T_14_07_masksDocumentsAndHidesShortOrCompositeSecrets() {
        assertThat(diff(new Secrets("380000001P", Map.of("secret", "old")), new Secrets("480000001Q", Map.of("secret", "new"))))
                .containsExactly(new AuditChange("document", "38······1P", "48······1Q"),
                        new AuditChange("token", "[ocult]", "[ocult]"));
        assertThat(diff(new Secrets(null, null), new Secrets("1234", "secret")))
                .containsExactly(new AuditChange("document", null, "[ocult]"), new AuditChange("token", null, "[ocult]"));
        assertThat(diff(new Payment("1"), new Payment("12")))
                .containsExactly(new AuditChange("iban", "[ocult]", "[ocult]"));
        assertThat(diff(new Payment(null), new Payment("ES00 0000 2231")))
                .containsExactly(new AuditChange("iban", null, "···· ···· ···· ···· 2231"));
    }

    @Test void T_14_07_snapshotsAreDetachedAndMaskNestedCollectionValues() {
        var list = new ArrayList<>(List.of(new Payment("ES0000002231")));
        var snapshot = AuditDiff.snapshot(Map.of("payments", list));
        list.set(0, new Payment("ES0000008867"));
        assertThat(AuditDiff.between(snapshot, AuditDiff.snapshot(Map.of("payments", list))))
                .containsExactly(new AuditChange("payments", List.of(Map.of("iban", "···· ···· ···· ···· 2231")),
                        List.of(Map.of("iban", "···· ···· ···· ···· 8867"))));
        assertThat(snapshot.toString()).doesNotContain("ES000", "2231");
    }

    @Test void T_14_07_createdDeletedAndAbsentNestedFieldsProduceLeafPaths() {
        assertThat(diff(null, new Payment("ES0000002231")))
                .containsExactly(new AuditChange("iban", null, "···· ···· ···· ···· 2231"));
        assertThat(diff(Map.of("payment", new Payment("ES0000002231")), Map.of()))
                .containsExactly(new AuditChange("payment.iban", "···· ···· ···· ···· 2231", null));
        assertThat(diff(Map.of("ignored", new Object()), Map.of())).isEmpty();
    }

    static class Base { @AuditField String name = "before"; }
    static class Child extends Base {
        @AuditField static String ignoredStatic = "static";
        String ignored = "secret";
        @AuditField Object data;
    }

    @Test void T_14_07_includesInheritedFieldsAndJsonScalarsArraysAndNullElements() {
        Child source = new Child();
        source.data = new Object[]{Instant.parse("2026-01-01T00:00:00Z"), UUID.fromString("00000000-0000-0000-0000-000000000001"),
                MASK_IBAN, 'x', true, 5, null};
        var before = AuditDiff.snapshot(source);
        source.name = "after";
        source.data = new int[]{1, 2};
        assertThat(AuditDiff.between(before, AuditDiff.snapshot(source))).containsExactly(
                new AuditChange("data", Arrays.asList("2026-01-01T00:00:00Z", "00000000-0000-0000-0000-000000000001", "MASK_IBAN", "x", true, 5, null), List.of(1, 2)),
                new AuditChange("name", "before", "after"));
    }

    @Test void T_14_07_rejectsCyclesWithoutPrintingSensitiveValues() {
        var cycle = new LinkedHashMap<String, Object>();
        cycle.put("self", cycle);
        assertThatThrownBy(() -> AuditDiff.snapshot(cycle)).isInstanceOf(IllegalArgumentException.class).hasMessage("Cyclic audit snapshot");
    }

    private Member member(String iban, String phone, String hash) {
        return new Member(new Payment(iban), List.of(new Phone("111"), new Phone(phone)), hash, "ignored-secret");
    }
    private List<AuditChange> diff(Object before, Object after) {
        return AuditDiff.between(AuditDiff.snapshot(before), AuditDiff.snapshot(after));
    }
}
