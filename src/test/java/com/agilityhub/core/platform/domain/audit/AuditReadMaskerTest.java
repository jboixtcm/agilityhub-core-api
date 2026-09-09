package com.agilityhub.core.platform.domain.audit;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuditReadMaskerTest {
    @Test void T_14_07_masksWholeObjectDiffsNestedDetailsAndSensitivePathsIdempotently() {
        var row = new LinkedHashMap<String, Object>();
        row.put("details", Map.of("apiKey", "fictional-key", "privateKey", "fictional-private", "clientSecret", "fictional-secret",
                "iban", "123", "holderTaxId", "123", "passwordHash", Map.of("encoded", "fictional-password"),
                "chip", "000000000000001", "safe", List.of(2, false), "alreadyHidden", "[ocult]"));
        row.put("changes", List.of(Map.of("path", "member", "before", Map.of("idDocument", Map.of("number", "AB123456CD")),
                "after", Map.of("paymentMethod", Map.of("iban", "ES0000000000000000002231"))),
                Map.of("path", "passwordHash", "before", "fictional-old", "after", "fictional-new")));
        var result = AuditReadMasker.sanitize(row);
        assertThat(result.toString()).doesNotContain("fictional-", "ES0000", "AB123456CD", "000000000000001")
                .contains("2231", "AB······CD", "[ocult]", "safe=[2, false]");
        assertThat(AuditReadMasker.sanitize(result)).isEqualTo(result);
        assertThat(row.toString()).contains("fictional-private", "ES0000");
        assertThat(AuditReadMasker.sanitize(Map.of("id", "entry"))).containsOnlyKeys("id");
    }
    @Test void T_14_07_preservesNullsAndOrdinaryDetailValues() {
        var details = new LinkedHashMap<String, Object>(); details.put("iban", null); details.put("count", 3);
        details.put("unknown", com.fasterxml.jackson.databind.node.NullNode.instance);
        details.put("nested", List.of(Map.of("after", "plain text")));
        assertThat(AuditReadMasker.sanitize(Map.of("details", details))).containsEntry("details", details);
    }
}
