package com.agilityhub.core.migration.domain;

import java.util.*;
import java.util.stream.IntStream;

/** S18 stage selection. Catalog/input validation (1) always runs. */
public record MigrationSteps(SortedSet<Integer> values) {
    public MigrationSteps {
        if (values==null || values.isEmpty() || values.stream().anyMatch(value->value==null || value<1 || value>10)) { throw usage(); }
        var normalized=new TreeSet<>(values); normalized.add(1);
        values=Collections.unmodifiableSortedSet(normalized);
    }
    public boolean includes(int step) { return values.contains(step); }
    public static MigrationSteps all() { return new MigrationSteps(new TreeSet<>(IntStream.rangeClosed(1,10).boxed().toList())); }
    public static MigrationSteps parse(String value) {
        if (value==null || value.isBlank()) { throw usage(); }
        var selected=new TreeSet<Integer>();
        for(var part:value.split(",",-1)) {
            if (!part.matches("(?:[1-9]|10)(?:-(?:[1-9]|10))?")) { throw usage(); }
            var range=part.split("-",-1); int first=Integer.parseInt(range[0]), last=range.length==1 ? first : Integer.parseInt(range[1]);
            if (last<first) { throw usage(); }
            IntStream.rangeClosed(first,last).forEach(selected::add);
        }
        return new MigrationSteps(selected);
    }
    private static IllegalArgumentException usage() { return new IllegalArgumentException("--steps must contain stages 1-10, comma-separated values or ascending ranges"); }
}
