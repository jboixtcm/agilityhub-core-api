package com.agilityhub.core.migration.domain;

import java.time.LocalDate;
import java.util.*;

/** Immutable execution evidence for one invocation; completion of an invocation need not complete the pipeline. */
public record MigrationExecution(SortedSet<Integer> selectedSteps, SortedSet<Integer> completedThisRun,
        SortedSet<Integer> completedBefore, LocalDate cutover, String rootRunId, String priorRunId,
        long ordinal, String approvalDigest, boolean staged) {
    public MigrationExecution {
        selectedSteps=immutable(selectedSteps); completedThisRun=immutable(completedThisRun); completedBefore=immutable(completedBefore);
        if (!selectedSteps.containsAll(completedThisRun) || ordinal<1) { throw new IllegalArgumentException("Invalid migration execution metadata"); }
    }
    public SortedSet<Integer> completedSteps() {
        var complete=new TreeSet<>(completedBefore); complete.addAll(completedThisRun); return Collections.unmodifiableSortedSet(complete);
    }
    public boolean pipelineComplete() { return completedSteps().containsAll(MigrationSteps.all().values()); }
    public MigrationExecution stepCompleted(int step) {
        if (!selectedSteps.contains(step)) { throw new IllegalArgumentException("Cannot complete an unselected migration stage"); }
        var complete=new TreeSet<>(completedThisRun); complete.add(step);
        return new MigrationExecution(selectedSteps,complete,completedBefore,cutover,rootRunId,priorRunId,ordinal,approvalDigest,staged);
    }
    private static SortedSet<Integer> immutable(Collection<Integer> values) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(values==null ? Set.of() : values));
    }
}
