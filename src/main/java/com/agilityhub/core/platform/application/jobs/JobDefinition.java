package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.Module;

/**
 * One R-15-01 catalog row. `localTimeParameter` holds the local hour (a `{dayOfWeek, time}` JSON for weekly
 * jobs); `dayParameter` the day of month of monthly jobs (0 = never); `module` is null when no module guards the job.
 */
public record JobDefinition(JobName name, String routeId, Cadence cadence, String localTimeParameter, String dayParameter,
        Module module, CatchUpWindow catchUpWindow, String switchParameter) { }
