package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.ClearIbanRows;
import com.agilityhub.core.payments.application.BankAccountVault;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;

/**
 * INC-31 (A37, ruling E43, E11-T02): `census:seal-ibans` seals the IBANs the signup and D10 stored in clear before E11-T02 —
 * flat, under `sepa`, and in a pending readmission — and is idempotent. Fictional IBANs only.
 */
class SealIbansCommandIT extends AbstractIntegrationTest {
    static final String FLAT = "ES9121000418450200051332", NESTED = "ES6800490001502611001332", READMISSION = "ES7921000813610123456789";
    @Autowired MongoTemplate mongo; @Autowired ClearIbanRows rows; @Autowired BankAccountVault vault; @Autowired SealIbansCommand command;

    @BeforeEach void seed() {
        mongo.remove(new Query(), "members");
        mongo.insert(new Document("_id", "flat").append("clubId", "seal-a").append("paymentMethod", new Document("type", "SEPA_DD").append("iban", FLAT)
                .append("holderName", "Fictional Holder").append("mandateRef", "seal-a-1-1")), "members");
        mongo.insert(new Document("_id", "nested").append("clubId", "seal-b").append("paymentMethod", new Document("type", "SEPA_DD")
                .append("sepa", new Document("iban", NESTED).append("holderName", "Fictional Holder"))), "members");
        mongo.insert(new Document("_id", "readmitted").append("clubId", "seal-a").append("paymentMethod", new Document("type", "MANUAL").append("channel", "cash"))
                .append("readmissionRequest", new Document("submitted", new Document("paymentMethod", new Document("type", "SEPA_DD").append("iban", READMISSION)))), "members");
        String sealed = vault.encrypt(FLAT, "seal-a", "sealed");
        mongo.insert(new Document("_id", "sealed").append("clubId", "seal-a").append("paymentMethod", new Document("type", "SEPA_DD").append("ibanEncrypted", sealed)
                .append("ibanLast4", "1332")), "members");
        mongo.insert(new Document("_id", "none").append("clubId", "seal-a").append("paymentMethod", new Document("type", "SEPA_DD").append("holderName", "Fictional")), "members");
    }

    String run(SealIbansCommand runner, String... arguments) {
        var out = new ByteArrayOutputStream(); var previous = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { runner.run(new DefaultApplicationArguments(arguments)); } finally { System.setOut(previous); }
        return out.toString(StandardCharsets.UTF_8);
    }
    Document member(String id) { return mongo.findById(id, Document.class, "members"); }

    @Test void INC_31_A37_theCommandSealsEveryClearIbanOnceAndWritesNothingInADryRun() {
        String dry = run(command);
        assertThat(dry).contains("WOULD_SEAL flat club=seal-a places=paymentMethod", "WOULD_SEAL nested club=seal-b places=paymentMethod.sepa",
                "WOULD_SEAL readmitted club=seal-a places=readmissionRequest.submitted.paymentMethod", "Members with an IBAN in clear: 3 (dry run")
                .doesNotContain("sealed club", "none club", FLAT, NESTED, READMISSION);
        assertThat(member("flat").get("paymentMethod", Document.class).getString("iban")).isEqualTo(FLAT);

        String applied = run(command, "--apply");
        assertThat(applied).contains("SEALED flat", "SEALED nested", "SEALED readmitted", "Members with an IBAN in clear: 3, sealed: 3")
                .doesNotContain(FLAT, NESTED, READMISSION);
        var flat = member("flat").get("paymentMethod", Document.class);
        assertThat(flat).doesNotContainKey("iban").containsEntry("ibanLast4", "1332").containsEntry("holderName", "Fictional Holder").containsEntry("mandateRef", "seal-a-1-1");
        assertThat(vault.resolve(flat, "seal-a", "flat")).isEqualTo(FLAT);
        var nested = member("nested").get("paymentMethod", Document.class).get("sepa", Document.class);
        assertThat(nested).doesNotContainKey("iban").containsEntry("ibanLast4", "1332");
        assertThat(vault.resolve(member("nested").get("paymentMethod", Document.class), "seal-b", "nested")).isEqualTo(NESTED);
        var submitted = member("readmitted").getEmbedded(java.util.List.of("readmissionRequest", "submitted", "paymentMethod"), Document.class);
        assertThat(submitted).doesNotContainKey("iban").containsEntry("ibanLast4", "6789");
        assertThat(vault.resolve(submitted, "seal-a", "readmitted")).isEqualTo(READMISSION);
        for (String id : java.util.List.of("flat", "nested", "readmitted")) { assertThat(member(id).toJson()).doesNotContain(FLAT, NESTED, READMISSION); }

        // Idempotent: nothing is left in clear, and the sealed rows are not touched again.
        var before = member("flat").toJson();
        assertThat(run(command, "--apply")).contains("Members with an IBAN in clear: 0, sealed: 0");
        assertThat(member("flat").toJson()).isEqualTo(before);
        assertThat(rows.find()).isEmpty();
    }

    @Test void INC_31_A37_withoutTheKeyOrWithBadArgumentsTheCommandRefusesAndWritesNothing() {
        var keyless = new SealIbansCommand(rows, com.agilityhub.core.payments.application.TestBankVaults.of(""));
        assertThatThrownBy(() -> run(keyless, "--apply")).isInstanceOf(IllegalStateException.class).hasMessage("BILLING_BANK_KEY is required to seal the IBANs");
        assertThat(run(keyless)).contains("Members with an IBAN in clear: 3 (dry run");
        assertThatThrownBy(() -> run(command, "--apply", "--dry-run")).hasMessage(SealIbansCommand.USAGE);
        assertThatThrownBy(() -> run(command, "extra")).hasMessage(SealIbansCommand.USAGE);
        assertThat(member("flat").get("paymentMethod", Document.class).getString("iban")).isEqualTo(FLAT);
    }

    /** A member changed between the listing and the seal is skipped, and a rerun seals its new value. */
    @Test void INC_31_A37_aRowChangedMeanwhileIsSkippedAndSealedByARerun() {
        var row = rows.find().stream().filter(r -> r.get("_id").equals("flat")).findFirst().orElseThrow();
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("flat")),
                new org.springframework.data.mongodb.core.query.Update().set("paymentMethod.iban", NESTED), "members");
        assertThat(rows.seal(row, iban -> vault.seal(iban, "seal-a", "flat"))).isFalse();
        assertThat(member("flat").get("paymentMethod", Document.class).getString("iban")).isEqualTo(NESTED);
        assertThat(run(command, "--apply")).contains("SEALED flat");
        assertThat(vault.resolve(member("flat").get("paymentMethod", Document.class), "seal-a", "flat")).isEqualTo(NESTED);
    }
}
