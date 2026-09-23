package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.jobs.JobRunRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.springframework.stereotype.Service;

/** Guards of the reserved S15 §6 routes: route id (JOB_UNKNOWN), module of the process (MODULE_DISABLED), tenant-scoped run, club. */
@Service
public class JobContractAccess {
    private final JobRunRepository runs;
    private final ClubConfigService configs;
    private final ClubRepository clubs;
    public JobContractAccess(JobRunRepository runs, ClubConfigService configs, ClubRepository clubs) {
        this.runs = runs; this.configs = configs; this.clubs = clubs;
    }
    public void tenant() { TenantContext.require(); }
    public JobDefinition job(String routeId) {
        var definition = JobCatalog.byRoute(routeId);
        if (definition.module() != null && !configs.get(TenantContext.require()).modules().contains(definition.module())) {
            throw new ApiException(ErrorCode.MODULE_DISABLED);
        }
        return definition;
    }
    public void run(String routeId, String runId) {
        var definition = job(routeId);
        runs.forJob(definition.name(), runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    /** Platform console: an unknown club is 404; the process guards then run inside that club's scope. */
    public void platformJob(String clubId, String routeId) {
        clubs.findById(clubId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        try (var scope = TenantContext.open(clubId)) { job(routeId); }
    }
}
