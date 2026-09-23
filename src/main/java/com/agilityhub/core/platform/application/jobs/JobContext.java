package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.ClubConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** One execution for one club: occurrence, club-local date, dry-run flag and the club configuration. */
public record JobContext(String clubId, ZoneId zone, Instant scheduledFor, LocalDate localDate, boolean dryRun,
        ClubConfig config, JobRunRecorder recorder) {
    /** Reads a club parameter and records it in `parametersSnapshot`, so the run stays explainable afterwards. */
    public <T> T parameter(String key, Class<T> type) {
        T value = config.get(key, type);
        recorder.parameter(key, value);
        return value;
    }
}
