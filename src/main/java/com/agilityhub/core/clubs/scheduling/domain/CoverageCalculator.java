package com.agilityhub.core.clubs.scheduling.domain;

import java.util.*;

public final class CoverageCalculator {
    private CoverageCalculator() { }
    public record ClassPlaces(int capacity, List<String> levelIds) { public ClassPlaces { levelIds = List.copyOf(levelIds); } }
    public record Dogs(int total, int active, int booked) { }
    public record Thresholds(int ok, int tight, int shortThreshold) { }
    public enum Status { OK, TIGHT, SHORT, EXPAND, NO_DOGS }
    public record Coverage(String levelId, int maxSeats, double propSeats, int dogsTotal, int dogsActive,
            Integer maxRatioPct, Integer propRatioPct, Status status, Integer booked) { }
    public static List<Coverage> calculate(List<String> levelIds, List<ClassPlaces> classes, Map<String, Dogs> dogs,
            Thresholds thresholds, boolean week) {
        return levelIds.stream().map(id -> {
            int max = 0; double proportional = 0;
            for (var item : classes) {
                if (item.levelIds().contains(id)) { max += item.capacity(); proportional += (double) item.capacity() / item.levelIds().size(); }
            }
            var count = dogs.getOrDefault(id, new Dogs(0, 0, 0));
            Integer maximum = ratio(max, count.total()), prop = ratio(proportional, count.active());
            return new Coverage(id, max, proportional, count.total(), count.active(), maximum, prop,
                    status(prop, thresholds), week ? count.booked() : null);
        }).toList();
    }
    private static Integer ratio(double seats, int dogs) { return dogs == 0 ? null : (int) Math.round(100 * seats / dogs); }
    public static Status status(Integer ratio, Thresholds thresholds) {
        return ratio == null ? Status.NO_DOGS : ratio > thresholds.ok() ? Status.OK
                : ratio >= thresholds.tight() ? Status.TIGHT : ratio >= thresholds.shortThreshold() ? Status.SHORT : Status.EXPAND;
    }
}
