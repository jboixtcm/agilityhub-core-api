package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.SealedIbanRows;
import com.agilityhub.core.payments.application.BankAccountVault;
import com.agilityhub.core.payments.application.TestBankVaults;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;

/**
 * PRIV-11 (owner's decision of 13-11-2026): `census:rekey-ibans` seals every stored IBAN again with the current `BILLING_BANK_KEY`
 * — flat, under `sepa` and in a pending readmission, with or without the key id that values sealed before PRIV-11 lack — reading
 * each with `BILLING_BANK_KEY_PREVIOUS` during a rotation. Dry run by default, idempotent, counts in the output, never an IBAN or
 * a ciphertext. Fictional IBANs and keys only.
 */
class RekeyIbansCommandIT extends AbstractIntegrationTest {
    static final String OLD = key("priv-11-old-fictional-bank-key-01"), NEW = key("priv-11-new-fictional-bank-key-02"),
            THIRD = key("priv-11-third-fictional-bankkey03");
    static final String FLAT = "ES9121000418450200051332", NESTED = "ES6800490001502611001332", READMISSION = "ES7921000813610123456789",
            CLEAR = "ES1000492352082414205416";
    @Autowired MongoTemplate mongo; @Autowired SealedIbanRows rows;
    final BankAccountVault old = TestBankVaults.of(OLD), rotating = TestBankVaults.of(NEW, OLD), after = TestBankVaults.of(NEW);
    final RekeyIbansCommand command = new RekeyIbansCommand(null, null);
    List<String> storedBefore;

    static String key(String text) { return Base64.getEncoder().encodeToString(text.substring(0, 32).getBytes(StandardCharsets.US_ASCII)); }

    /** A value as every release sealed it before PRIV-11: the Base64 after the key id, which those releases did not write. */
    static String withoutKeyId(String prefixed) { return prefixed.substring(prefixed.indexOf(':') + 1); }

    @BeforeEach void seed() {
        mongo.remove(new Query(), "members");
        mongo.insert(new Document("_id", "legacy").append("clubId", "rekey-a").append("paymentMethod", new Document("type", "SEPA_DD")
                .append("ibanEncrypted", withoutKeyId(old.encrypt(FLAT, "rekey-a", "legacy"))).append("ibanLast4", "1332")
                .append("holderName", "Fictional Holder").append("mandateRef", "rekey-a-1-1")), "members");
        mongo.insert(new Document("_id", "nested").append("clubId", "rekey-b").append("paymentMethod", new Document("type", "SEPA_DD")
                .append("sepa", new Document("ibanEncrypted", old.encrypt(NESTED, "rekey-b", "nested")).append("ibanLast4", "1332")
                        .append("holderName", "Fictional Holder"))), "members");
        mongo.insert(new Document("_id", "readmitted").append("clubId", "rekey-a").append("paymentMethod", new Document("type", "MANUAL").append("channel", "cash"))
                .append("readmissionRequest", new Document("submitted", new Document("paymentMethod", new Document("type", "SEPA_DD")
                        .append("ibanEncrypted", old.encrypt(READMISSION, "rekey-a", "readmitted")).append("ibanLast4", "6789")))), "members");
        mongo.insert(new Document("_id", "current").append("clubId", "rekey-a").append("paymentMethod", new Document("type", "SEPA_DD")
                .append("ibanEncrypted", after.encrypt(FLAT, "rekey-a", "current")).append("ibanLast4", "1332")), "members");
        mongo.insert(new Document("_id", "clear").append("clubId", "rekey-a").append("paymentMethod", new Document("type", "SEPA_DD").append("iban", CLEAR)), "members");
        mongo.insert(new Document("_id", "none").append("clubId", "rekey-a").append("paymentMethod", new Document("type", "MANUAL").append("channel", "cash")), "members");
        storedBefore = mongo.findAll(Document.class, "members").stream().flatMap(row -> SealedIbanRows.sealed(row).values().stream()).toList();
    }

    String run(BankAccountVault vault, String... arguments) {
        var out = new ByteArrayOutputStream(); var previous = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new RekeyIbansCommand(rows, vault).run(new DefaultApplicationArguments(arguments)); } finally { System.setOut(previous); }
        return out.toString(StandardCharsets.UTF_8);
    }
    Document member(String id) { return mongo.findById(id, Document.class, "members"); }
    String json() { return mongo.findAll(Document.class, "members").toString(); }

    @Test void PRIV_11_aDryRunWritesNothingAndApplyReEncryptsEveryIbanOnceWithTheNewKey() {
        String before = json();
        String dry = run(rotating);
        assertThat(dry).contains("Current key: " + after.keyId() + ", previous key: " + old.keyId(),
                "WOULD_REKEY legacy club=rekey-a places=paymentMethod", "WOULD_REKEY nested club=rekey-b places=paymentMethod.sepa",
                "WOULD_REKEY readmitted club=rekey-a places=readmissionRequest.submitted.paymentMethod",
                "Sealed IBANs: 4, already with the current key: 1, to re-encrypt: 3, unreadable: 0 (dry run")
                .doesNotContain("current club", "clear club", "none club", FLAT, NESTED, READMISSION, CLEAR).doesNotContain(storedBefore);
        assertThat(json()).isEqualTo(before);

        String applied = run(rotating, "--apply");
        assertThat(applied).contains("REKEYED legacy", "REKEYED nested", "REKEYED readmitted",
                "Sealed IBANs: 4, already with the current key: 1, re-encrypted: 3, skipped (changed meanwhile, a rerun re-encrypts them): 0, unreadable: 0")
                .doesNotContain(FLAT, NESTED, READMISSION, CLEAR).doesNotContain(storedBefore);
        // The new key alone reads every value now; the rest of each method is untouched and nothing is in clear.
        var legacy = member("legacy").get("paymentMethod", Document.class);
        assertThat(legacy).containsEntry("ibanLast4", "1332").containsEntry("holderName", "Fictional Holder").containsEntry("mandateRef", "rekey-a-1-1")
                .doesNotContainKey("iban");
        assertThat(legacy.getString("ibanEncrypted")).startsWith(after.keyId() + ":");
        assertThat(after.resolve(legacy, "rekey-a", "legacy")).isEqualTo(FLAT);
        assertThat(after.resolve(member("nested").get("paymentMethod", Document.class), "rekey-b", "nested")).isEqualTo(NESTED);
        var submitted = member("readmitted").getEmbedded(List.of("readmissionRequest", "submitted", "paymentMethod"), Document.class);
        assertThat(submitted).containsEntry("ibanLast4", "6789");
        assertThat(after.resolve(submitted, "rekey-a", "readmitted")).isEqualTo(READMISSION);
        assertThat(after.resolve(member("current").get("paymentMethod", Document.class), "rekey-a", "current")).isEqualTo(FLAT);
        assertThat(member("clear").get("paymentMethod", Document.class)).containsEntry("iban", CLEAR).doesNotContainKey("ibanEncrypted");
        assertThat(json()).doesNotContain(FLAT, NESTED, READMISSION);

        // Idempotent: a second --apply re-encrypts nothing and writes nothing; after the rotation the new key alone finds nothing to do.
        String rekeyed = json();
        assertThat(run(rotating, "--apply")).contains("Sealed IBANs: 4, already with the current key: 4, re-encrypted: 0").doesNotContain("REKEYED");
        assertThat(json()).isEqualTo(rekeyed);
        assertThat(run(after)).contains("previous key: none", "Sealed IBANs: 4, already with the current key: 4, to re-encrypt: 0, unreadable: 0");
    }

    /** Without a previous key the command only adds the key id to the values sealed with the current key before PRIV-11. */
    @Test void PRIV_11_withoutAPreviousKeyTheCommandAddsTheKeyIdToTheValuesSealedBefore() {
        mongo.remove(new Query(Criteria.where("_id").in("nested", "readmitted", "current")), "members");
        String stored = member("legacy").get("paymentMethod", Document.class).getString("ibanEncrypted");
        assertThat(run(old, "--apply")).contains("REKEYED legacy", "Sealed IBANs: 1, already with the current key: 0, re-encrypted: 1");
        var legacy = member("legacy").get("paymentMethod", Document.class);
        assertThat(legacy.getString("ibanEncrypted")).startsWith(old.keyId() + ":").isNotEqualTo(stored);
        assertThat(old.resolve(legacy, "rekey-a", "legacy")).isEqualTo(FLAT);
    }

    /** A value no configured key reads is listed and left as it is; the others are re-encrypted and the command then fails. */
    @Test void PRIV_11_aValueNoConfiguredKeyReadsIsReportedAndLeftAsItIs() {
        String foreign = TestBankVaults.of(THIRD).encrypt(FLAT, "rekey-a", "foreign");
        mongo.insert(new Document("_id", "foreign").append("clubId", "rekey-a").append("paymentMethod", new Document("type", "SEPA_DD")
                .append("ibanEncrypted", foreign).append("ibanLast4", "1332")), "members");
        String before = json();
        assertThatThrownBy(() -> run(rotating)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Some sealed IBANs are not readable with the configured keys");
        assertThat(json()).isEqualTo(before);
        var out = new ByteArrayOutputStream(); var previous = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { assertThatThrownBy(() -> new RekeyIbansCommand(rows, rotating).run(new DefaultApplicationArguments("--apply"))).isInstanceOf(IllegalStateException.class); }
        finally { System.setOut(previous); }
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("UNREADABLE foreign club=rekey-a places=paymentMethod", "REKEYED legacy",
                "Sealed IBANs: 5, already with the current key: 1, re-encrypted: 3, skipped (changed meanwhile, a rerun re-encrypts them): 0, unreadable: 1")
                .doesNotContain(FLAT, foreign);
        assertThat(member("foreign").get("paymentMethod", Document.class).getString("ibanEncrypted")).isEqualTo(foreign);
        assertThat(after.resolve(member("legacy").get("paymentMethod", Document.class), "rekey-a", "legacy")).isEqualTo(FLAT);
    }

    @Test void PRIV_11_withoutTheKeyOrWithBadArgumentsTheCommandRefusesAndWritesNothing() {
        String before = json();
        assertThatThrownBy(() -> run(TestBankVaults.of(""))).isInstanceOf(IllegalStateException.class)
                .hasMessage("BILLING_BANK_KEY is required to re-encrypt the IBANs");
        assertThatThrownBy(() -> run(TestBankVaults.of(""), "--apply")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run(rotating, "--apply", "--dry-run")).hasMessage(RekeyIbansCommand.USAGE);
        assertThatThrownBy(() -> run(rotating, "extra")).hasMessage(RekeyIbansCommand.USAGE);
        assertThatThrownBy(() -> run(rotating, "--force")).hasMessage(RekeyIbansCommand.USAGE);
        assertThat(json()).isEqualTo(before);
        assertThat(command.name()).isEqualTo("census:rekey-ibans");
    }

    /** A member changed between the listing and the write is skipped, and a rerun re-encrypts its new value. */
    @Test void PRIV_11_aRowChangedMeanwhileIsSkippedAndReEncryptedByARerun() {
        var row = rows.find().stream().filter(r -> r.get("_id").equals("legacy")).findFirst().orElseThrow();
        String edited = old.encrypt(NESTED, "rekey-a", "legacy");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("legacy")), new Update().set("paymentMethod.ibanEncrypted", edited), "members");
        assertThat(rows.replace(row, java.util.Map.of("paymentMethod", rotating.reseal(SealedIbanRows.sealed(row).get("paymentMethod"), "rekey-a", "legacy"))))
                .isFalse();
        assertThat(member("legacy").get("paymentMethod", Document.class).getString("ibanEncrypted")).isEqualTo(edited);
        assertThat(rows.replace(row, java.util.Map.of())).isFalse();
        assertThat(run(rotating, "--apply")).contains("REKEYED legacy");
        assertThat(after.resolve(member("legacy").get("paymentMethod", Document.class), "rekey-a", "legacy")).isEqualTo(NESTED);
    }
}
