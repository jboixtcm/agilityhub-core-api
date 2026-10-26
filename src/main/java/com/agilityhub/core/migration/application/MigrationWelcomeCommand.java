package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.MigrationWelcomeRecipients;
import com.agilityhub.core.identity.application.MigrationWelcomeDelivery;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/** S18 R-18-16: explicit operator command; never scheduled or invoked by the import itself. */
@Component
public class MigrationWelcomeCommand implements CoreCommand {
    private final MigrationClubAccess clubs;
    private final MigrationWelcomeRecipients recipients;
    private final MigrationWelcomeDelivery delivery;
    private final Consumer<Duration> pause;
    @Autowired
    public MigrationWelcomeCommand(MigrationClubAccess clubs, MigrationWelcomeRecipients recipients, MigrationWelcomeDelivery delivery) {
        this(clubs, recipients, delivery, duration -> {
            try { Thread.sleep(duration); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Welcome batches interrupted; completed deliveries remain recorded", interrupted);
            }
        });
    }
    MigrationWelcomeCommand(MigrationClubAccess clubs, MigrationWelcomeRecipients recipients,
            MigrationWelcomeDelivery delivery, Consumer<Duration> pause) {
        this.clubs = clubs; this.recipients = recipients; this.delivery = delivery; this.pause = pause;
    }
    @Override public String name() { return "migration:welcome"; }
    @Override public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().isEmpty() || !Set.of("core.command", "club", "batch", "interval", "only-numbers", "dry-run")
                .containsAll(args.getOptionNames()) || args.containsOption("dry-run") && !args.getOptionValues("dry-run").isEmpty()) { throw usage(); }
        String slug = option(args, "club", null);
        if (slug == null) { throw usage(); }
        int batch;
        Duration interval;
        var numbers = new HashSet<Integer>();
        try {
            batch = Integer.parseInt(option(args, "batch", "50"));
            String value = option(args, "interval", "60s");
            if (!value.matches("[1-9][0-9]*(s|m)")) { throw usage(); }
            long amount = Long.parseLong(value.substring(0, value.length() - 1));
            interval = value.endsWith("m") ? Duration.ofMinutes(amount) : Duration.ofSeconds(amount);
            String selected = option(args, "only-numbers", null);
            if (selected != null) {
                for (String number : selected.split(",", -1)) {
                    int parsed = Integer.parseInt(number.strip());
                    if (parsed < 1) { throw usage(); }
                    numbers.add(parsed);
                }
            }
            if (batch < 1 || batch > 50 || interval.compareTo(Duration.ofHours(1)) > 0) { throw usage(); }
        } catch (NumberFormatException | ArithmeticException invalid) { throw usage(); }
        boolean dryRun = args.containsOption("dry-run");
        try (var tenant = TenantContext.open(clubs.resolve(slug))) {
            record Candidate(MigrationWelcomeRecipients.Recipient recipient, boolean staff) { }
            var ordered = recipients.list(numbers).stream().map(recipient -> new Candidate(recipient, delivery.staff(recipient.accountId())))
                    .sorted(Comparator.comparing(Candidate::staff).reversed()
                            .thenComparing(candidate -> candidate.recipient().number(), Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(candidate -> candidate.recipient().memberId())).toList();
            var counts = new EnumMap<MigrationWelcomeDelivery.Result, Integer>(MigrationWelcomeDelivery.Result.class);
            var accounts = new HashSet<String>();
            int attempted = 0;
            boolean staffBatch = true;
            for (var candidate : ordered) {
                var recipient = candidate.recipient();
                if (!accounts.add(recipient.accountId())) { continue; }
                if (!dryRun && attempted > 0 && (attempted >= batch || staffBatch && !candidate.staff())) {
                    pause.accept(interval); attempted = 0;
                }
                staffBatch = candidate.staff();
                var result = delivery.deliver(recipient.memberId(), recipient.accountId(), recipient.firstName(), recipient.gender(), dryRun);
                counts.merge(result, 1, Integer::sum);
                if (result == MigrationWelcomeDelivery.Result.SENT || result == MigrationWelcomeDelivery.Result.REVIEW_REQUIRED) { attempted++; }
            }
            // Aggregate counts only: no names, addresses, member numbers or credential links in the operator report.
            System.out.println((dryRun ? "DRY RUN" : "WELCOME") + " " + counts);
            if (!dryRun && counts.getOrDefault(MigrationWelcomeDelivery.Result.REVIEW_REQUIRED, 0) > 0) {
                throw new IllegalStateException("Welcome delivery requires review; reruns do not resend uncertain attempts");
            }
        }
    }
    private String option(ApplicationArguments args, String name, String fallback) {
        var values = args.getOptionValues(name);
        if (values == null) { return fallback; }
        if (values.size() != 1 || values.getFirst().isBlank()) { throw usage(); }
        return values.getFirst();
    }
    private IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: migration:welcome --club=slug [--batch=1..50] [--interval=60s] [--only-numbers=1,2] [--dry-run]");
    }
}
