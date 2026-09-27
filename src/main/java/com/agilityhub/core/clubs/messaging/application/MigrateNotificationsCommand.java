package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.LegacyNotificationRows;
import com.agilityhub.core.shared.application.CoreCommand;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * `bin/core messaging:migrate-notifications [--dry-run | --apply]` (E7-T01): converts the flat `notifications` rows written
 * before E7-T01 (E1–E6, no `deliveries`) into the S11 §3 shape the helpers now write, for local and staging databases; R1 is
 * not live, so no production row exists. Dry run by default: it prints the rows it would convert (id, club, code, channel,
 * status — never an address) and writes nothing. Idempotent: a converted row is no longer a legacy one.
 */
@Component
public class MigrateNotificationsCommand implements CoreCommand {
    static final String USAGE = "Usage: messaging:migrate-notifications [--dry-run | --apply]";
    private final LegacyNotificationRows rows;
    public MigrateNotificationsCommand(LegacyNotificationRows rows) { this.rows = rows; }
    @Override public String name() { return "messaging:migrate-notifications"; }
    @Override public void run(ApplicationArguments arguments) {
        boolean apply = arguments.containsOption("apply");
        if (!arguments.getNonOptionArgs().isEmpty() || !Set.of("core.command", "dry-run", "apply").containsAll(arguments.getOptionNames())
                || apply && arguments.containsOption("dry-run")) {
            throw new IllegalArgumentException(USAGE);
        }
        int found = 0, converted = 0;
        for (var row : rows.find()) {
            found++;
            boolean done = apply && rows.convert(row);
            if (done) { converted++; }
            System.out.println((apply ? done ? "CONVERTED " : "SKIPPED " : "WOULD_CONVERT ") + row.get("_id") + " club=" + Objects.requireNonNullElse(row.getString("clubId"), "SYSTEM")
                    + " code=" + row.getString("code") + " channel=" + row.getString("channel") + " status=" + row.getString("status"));
        }
        System.out.println(apply ? "Legacy notification rows: " + found + ", converted: " + converted
                : "Legacy notification rows: " + found + " (dry run: nothing written; --apply converts them)");
    }
}
