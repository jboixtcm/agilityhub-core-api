package com.agilityhub.core.arch;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E8-T02 (S12 R-12-10, T-12-12): the immutability rule catches what it guards — a repository of `payments` with an `update*`
 * over the lines or a `save*` of a whole invoice violates it, a state transition does not. `ArchitectureTest` runs the same
 * rule over the production classes.
 */
class InvoiceImmutabilityRuleTest {
    private static final String FIXTURES = "com.agilityhub.core.payments.persistence.InvoiceMutationFixtures$";

    @Test void T_12_12_aRepositoryThatUpdatesLinesOrSavesAWholeInvoiceBreaksTheRule() throws Exception {
        var classes = new ClassFileImporter().importClasses(Class.forName(FIXTURES + "LinesRepository"), Class.forName(FIXTURES + "WholeInvoiceRepository"));
        var result = ArchitectureRules.INVOICE_LINES_IMMUTABLE.evaluate(classes);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).hasSize(2)
                .anySatisfy(line -> assertThat(line).contains("updateLines")).anySatisfy(line -> assertThat(line).contains("saveInvoice"));
    }

    @Test void T_12_12_aStateTransitionIsAllowed() throws Exception {
        var classes = new ClassFileImporter().importClasses(Class.forName(FIXTURES + "StateRepository"));
        assertThat(ArchitectureRules.INVOICE_LINES_IMMUTABLE.evaluate(classes).hasViolation()).isFalse();
    }
}
