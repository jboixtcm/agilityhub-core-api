package com.agilityhub.core.payments.application.sepa;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT session-10 survivors of {@link Pain008Document} (S12 R-12-11/12, T-12-11): a document that fails the
 * pain.008.001.02 schema is reported by its line and column only (never the offending value), which the writer logs.
 */
class Pain008DocumentSurvivors3Test {
    @Test void T_12_11_aDocumentFailingTheSchemaReportsItsLineAndColumn() {
        // Line 3 holds an element the schema does not allow under `Document` (it wants `CstmrDrctDbtInitn`).
        byte[] xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.008.001.02\">\n"
                + "    <Unexpected/>\n"
                + "</Document>\n").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> Pain008Document.validate(xml)).isInstanceOfSatisfying(Pain008Document.InvalidDocument.class, invalid -> {
            assertThat(invalid.line()).isEqualTo(3);
            assertThat(invalid.column()).isPositive();
            assertThat(invalid).hasMessage("pain.008 failed its schema at line 3, column " + invalid.column());
        });
    }
}
