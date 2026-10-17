package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.ClearIbanRows;
import com.agilityhub.core.shared.application.BankAccounts;
import com.agilityhub.core.shared.application.CoreCommand;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * `bin/core census:seal-ibans [--dry-run | --apply]` (INC-31, A37, E11-T02): seals the members' IBANs still stored in clear —
 * written by the signup and D10 before E11-T02 — so every account is `ibanEncrypted` + `ibanLast4` (ruling E43) before the
 * launch. Dry run by default: it prints the members it would seal (id, club and the places, never an IBAN) and writes nothing.
 * `--apply` needs `BILLING_BANK_KEY` and seals each member with one conditioned update (`SKIPPED` = changed meanwhile: a rerun
 * seals it). Idempotent: a sealed member has no clear IBAN left, so a second run finds nothing.
 */
@Component
public class SealIbansCommand implements CoreCommand {
    static final String USAGE = "Usage: census:seal-ibans [--dry-run | --apply]";
    private final ClearIbanRows rows;
    private final BankAccounts bankAccounts;
    public SealIbansCommand(ClearIbanRows rows, BankAccounts bankAccounts) { this.rows = rows; this.bankAccounts = bankAccounts; }
    @Override public String name() { return "census:seal-ibans"; }
    @Override public void run(ApplicationArguments arguments) {
        boolean apply = arguments.containsOption("apply");
        if (!arguments.getNonOptionArgs().isEmpty() || !Set.of("core.command", "dry-run", "apply").containsAll(arguments.getOptionNames())
                || apply && arguments.containsOption("dry-run")) {
            throw new IllegalArgumentException(USAGE);
        }
        if (apply && !bankAccounts.configured()) { throw new IllegalStateException("BILLING_BANK_KEY is required to seal the IBANs"); }
        int found = 0, sealed = 0;
        for (var row : rows.find()) {
            found++;
            String club = row.getString("clubId"), member = row.get("_id").toString();
            var places = ClearIbanRows.clear(row).keySet();
            boolean done = apply && rows.seal(row, iban -> bankAccounts.seal(iban, club, member));
            if (done) { sealed++; }
            System.out.println((apply ? done ? "SEALED " : "SKIPPED " : "WOULD_SEAL ") + member + " club=" + club + " places=" + String.join(",", places));
        }
        System.out.println(apply ? "Members with an IBAN in clear: " + found + ", sealed: " + sealed
                : "Members with an IBAN in clear: " + found + " (dry run: nothing written; --apply seals them)");
    }
}
