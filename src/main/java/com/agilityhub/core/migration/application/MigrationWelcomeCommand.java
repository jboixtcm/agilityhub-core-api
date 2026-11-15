package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.MigrationWelcomeRecipients;
import com.agilityhub.core.identity.application.MigrationWelcomeDelivery;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * S18 R-18-16: explicit operator command; never scheduled or invoked by the import itself. MIG-08 (owner decision,
 * 13-11-2026): without {@code --send} it only previews (who would receive the welcome, then the counts); {@code --send}
 * sends, and on the prod profile only after the operator types the club slug back on stdin.
 */
@Component
public class MigrationWelcomeCommand implements CoreCommand {
    private final MigrationClubAccess clubs;
    private final MigrationWelcomeRecipients recipients;
    private final MigrationWelcomeDelivery delivery;
    private final Environment environment;
    private final Consumer<Duration> pause;
    private final Supplier<String> typed;
    @Autowired
    public MigrationWelcomeCommand(MigrationClubAccess clubs, MigrationWelcomeRecipients recipients, MigrationWelcomeDelivery delivery,
            Environment environment) {
        this(clubs, recipients, delivery, environment, duration -> {
            try { Thread.sleep(duration); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Welcome batches interrupted; completed deliveries remain recorded", interrupted);
            }
        }, MigrationWelcomeCommand::readLine);
    }
    MigrationWelcomeCommand(MigrationClubAccess clubs, MigrationWelcomeRecipients recipients,
            MigrationWelcomeDelivery delivery, Environment environment, Consumer<Duration> pause, Supplier<String> typed) {
        this.clubs = clubs; this.recipients = recipients; this.delivery = delivery; this.environment = environment; this.pause = pause; this.typed = typed;
    }
    @Override public String name() { return "migration:welcome"; }
    @Override public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().isEmpty() || !Set.of("core.command", "club", "batch", "interval", "only-numbers", "dry-run", "send")
                .containsAll(args.getOptionNames()) || args.containsOption("dry-run") && !args.getOptionValues("dry-run").isEmpty()
                || args.containsOption("send") && !args.getOptionValues("send").isEmpty()
                || args.containsOption("dry-run") && args.containsOption("send")) { throw usage(); }
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
        boolean send = args.containsOption("send");
        try (var tenant = TenantContext.open(clubs.resolve(slug))) {
            var ordered = recipients.list(numbers).stream().map(recipient -> new Candidate(recipient, delivery.staff(recipient.accountId())))
                    .sorted(Comparator.comparing(Candidate::staff).reversed()
                            .thenComparing(candidate -> candidate.recipient().number(), Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(candidate -> candidate.recipient().memberId())).toList();
            if (!send || environment.matchesProfiles("prod", "production")) {
                // MIG-08: who would receive the welcome (member number, first name, staff), in send order; never an address or link.
                var eligible = new ArrayList<Candidate>();
                var preview = deliver(ordered, true, batch, interval, eligible);
                for (var candidate : eligible) {
                    var recipient = candidate.recipient();
                    System.out.println("WOULD SEND #" + (recipient.number() == null ? "-" : recipient.number()) + " "
                            + Objects.toString(recipient.firstName(), "") + (candidate.staff() ? " (staff)" : ""));
                }
                System.out.println("DRY RUN " + preview);
                if (!send) { return; }
                // MIG-08, prod profile: the slug typed back on stdin arms the send; anything else (or no input) sends nothing.
                System.out.println("Production: type the club slug to send " + eligible.size() + " welcome e-mails:");
                if (!slug.equals(typed.get())) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
            }
            var counts = deliver(ordered, false, batch, interval, new ArrayList<>());
            // Aggregate counts only: no names, addresses, member numbers or credential links in the send report.
            System.out.println("WELCOME " + counts);
            if (counts.getOrDefault(MigrationWelcomeDelivery.Result.REVIEW_REQUIRED, 0) > 0) {
                throw new IllegalStateException("Welcome delivery requires review; reruns do not resend uncertain attempts");
            }
        }
    }
    private record Candidate(MigrationWelcomeRecipients.Recipient recipient, boolean staff) { }
    /** One pass over the ordered candidates: the preview ({@code dryRun}) never claims, sends or sleeps; `eligible` collects its ELIGIBLE ones. */
    private Map<MigrationWelcomeDelivery.Result, Integer> deliver(List<Candidate> ordered, boolean dryRun, int batch, Duration interval, List<Candidate> eligible) {
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
            if (result == MigrationWelcomeDelivery.Result.ELIGIBLE) { eligible.add(candidate); }
            if (result == MigrationWelcomeDelivery.Result.SENT || result == MigrationWelcomeDelivery.Result.REVIEW_REQUIRED) { attempted++; }
        }
        return counts;
    }
    /** The operator's typed line; null at the end of input or when stdin cannot be read (then nothing is sent). */
    private static String readLine() {
        try { return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine(); }
        catch (IOException unreadable) { return null; }
    }
    private String option(ApplicationArguments args, String name, String fallback) {
        var values = args.getOptionValues(name);
        if (values == null) { return fallback; }
        if (values.size() != 1 || values.getFirst().isBlank()) { throw usage(); }
        return values.getFirst();
    }
    private IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: migration:welcome --club=slug [--batch=1..50] [--interval=60s] [--only-numbers=1,2] [--dry-run | --send]"
                + " (without --send it only previews; on the prod profile --send asks for the club slug on stdin)");
    }
}
