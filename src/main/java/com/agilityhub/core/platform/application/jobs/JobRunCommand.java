package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * `bin/core jobs:run <route-id> [--club=<slug>] [--dry-run]`: one manual execution per club (R-15-09 code path, same lock),
 * so staging and the smoke scripts can drive a process without waiting for the scheduler. No account: `actorAccountId` is null.
 */
@Component
public class JobRunCommand implements CoreCommand {
    private final ClubConfigService clubs; private final JobRunner runner;
    public JobRunCommand(ClubConfigService clubs, JobRunner runner) { this.clubs = clubs; this.runner = runner; }
    @Override public String name() { return "jobs:run"; }
    @Override public void run(ApplicationArguments args) {
        var values = args.getOptionValues("club");
        if (args.getNonOptionArgs().size() != 1 || !Set.of("core.command", "club", "dry-run").containsAll(args.getOptionNames()) || values != null && values.size() != 1) {
            throw new IllegalArgumentException("Usage: jobs:run <route-id> [--club=<slug>] [--dry-run]");
        }
        var definition = JobCatalog.byRoute(args.getNonOptionArgs().getFirst());
        boolean dryRun = args.containsOption("dry-run");
        var ids = values == null ? clubs.activeClubIds()
                : List.of(clubs.findClubIdBySlug(values.getFirst()).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND)));
        for (String id : ids) {
            var run = JobViews.view(runner.manual(id, definition.name(), dryRun, null));
            System.out.println("Job " + definition.routeId() + " club=" + id + " run=" + run.runId() + " status=" + run.status()
                    + " dryRun=" + run.dryRun() + " counters=" + run.effects().counters() + " errors=" + run.errors().size());
        }
    }
}
