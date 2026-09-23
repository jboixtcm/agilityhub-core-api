package com.agilityhub.core.platform.application.jobs;

/** Collects what a run read and counted: `parametersSnapshot` and `effects.counters` (R-15-07). */
public interface JobRunRecorder {
    void parameter(String key, Object value);
    void count(String key, long delta);
}
