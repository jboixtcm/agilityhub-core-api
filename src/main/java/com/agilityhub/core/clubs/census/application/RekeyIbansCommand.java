package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.SealedIbanRows;
import com.agilityhub.core.shared.application.BankAccounts;
import com.agilityhub.core.shared.application.CoreCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * `bin/core census:rekey-ibans [--dry-run | --apply]` (PRIV-11, owner's decision of 13-11-2026): seals every stored IBAN again with
 * the current `BILLING_BANK_KEY`, reading each with the key that sealed it (the current one, or `BILLING_BANK_KEY_PREVIOUS` during
 * a rotation; a value sealed before PRIV-11 carries no key id). The rotation procedure is in docs/DEPLOY.md.
 *
 * <p>Dry run by default: it reads every sealed IBAN with the configured keys, prints the members it would re-encrypt (id, club and
 * places, never an IBAN nor a ciphertext) and the counts, and writes nothing. `--apply` re-encrypts them, each member with one update
 * conditioned on the values read (`SKIPPED` = changed meanwhile: a rerun re-encrypts it). Idempotent: a value already sealed with
 * the current key is left as it is, so a second `--apply` re-encrypts nothing. A value that no configured key reads is listed as
 * `UNREADABLE`, left as it is, and the command ends with an error once it has printed the counts. Both modes need
 * `BILLING_BANK_KEY`. IBANs still stored in clear are `census:seal-ibans`' business.
 */
@Component
public class RekeyIbansCommand implements CoreCommand {
    static final String USAGE = "Usage: census:rekey-ibans [--dry-run | --apply]";
    private final SealedIbanRows rows;
    private final BankAccounts bankAccounts;
    public RekeyIbansCommand(SealedIbanRows rows, BankAccounts bankAccounts) { this.rows = rows; this.bankAccounts = bankAccounts; }
    @Override public String name() { return "census:rekey-ibans"; }
    @Override public void run(ApplicationArguments arguments) {
        boolean apply = arguments.containsOption("apply");
        if (!arguments.getNonOptionArgs().isEmpty() || !Set.of("core.command", "dry-run", "apply").containsAll(arguments.getOptionNames())
                || apply && arguments.containsOption("dry-run")) {
            throw new IllegalArgumentException(USAGE);
        }
        if (!bankAccounts.configured()) { throw new IllegalStateException("BILLING_BANK_KEY is required to re-encrypt the IBANs"); }
        String previous = bankAccounts.previousKeyId();
        System.out.println("Current key: " + bankAccounts.keyId() + ", previous key: " + (previous == null ? "none" : previous));
        int stored = 0, current = 0, pending = 0, rekeyed = 0, skipped = 0, unreadable = 0;
        for (var row : rows.find()) {
            String club = row.getString("clubId"), member = row.get("_id").toString();
            var replacements = new LinkedHashMap<String, String>();
            var failed = new ArrayList<String>();
            for (var place : SealedIbanRows.sealed(row).entrySet()) {
                stored++;
                if (bankAccounts.sealedWithCurrentKey(place.getValue())) { current++; continue; }
                try { replacements.put(place.getKey(), bankAccounts.reseal(place.getValue(), club, member)); }
                catch (IllegalStateException notReadable) { failed.add(place.getKey()); }
            }
            if (!failed.isEmpty()) {
                unreadable += failed.size();
                System.out.println("UNREADABLE " + member + " club=" + club + " places=" + String.join(",", failed));
            }
            if (replacements.isEmpty()) { continue; }
            String places = " club=" + club + " places=" + String.join(",", replacements.keySet());
            if (!apply) { pending += replacements.size(); System.out.println("WOULD_REKEY " + member + places); continue; }
            boolean done = rows.replace(row, replacements);
            if (done) { rekeyed += replacements.size(); } else { skipped += replacements.size(); }
            System.out.println((done ? "REKEYED " : "SKIPPED ") + member + places);
        }
        System.out.println(apply
                ? "Sealed IBANs: " + stored + ", already with the current key: " + current + ", re-encrypted: " + rekeyed
                        + ", skipped (changed meanwhile, a rerun re-encrypts them): " + skipped + ", unreadable: " + unreadable
                : "Sealed IBANs: " + stored + ", already with the current key: " + current + ", to re-encrypt: " + pending
                        + ", unreadable: " + unreadable + " (dry run: nothing written; --apply re-encrypts them)");
        if (unreadable > 0) { throw new IllegalStateException("Some sealed IBANs are not readable with the configured keys"); }
    }
}
