package com.agilityhub.core.clubs.catalogs.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** S05 R-05-01/R-05-09 and D3 weekly coverage. All configuration is supplied by the caller. */
public final class CapacityCalculator {
    private CapacityCalculator() { }

    public static int forLevels(List<Integer> capacities, boolean levelsEnabled, int defaultCapacity) {
        return levelsEnabled ? capacities.stream().mapToInt(Integer::intValue).min().orElse(defaultCapacity) : defaultCapacity;
    }
    public static int forRing(Integer trainingCapacity, int defaultCapacity) {
        return trainingCapacity == null ? defaultCapacity : trainingCapacity;
    }
    public record ClassPlaces(int capacity, List<String> levelIds) {
        public ClassPlaces {
            if (capacity < 0 || new HashSet<>(levelIds).size() != levelIds.size()) { throw new IllegalArgumentException("Invalid class places"); }
            levelIds = List.copyOf(levelIds);
        }
    }
    public record DogCounts(int total, int active) {
        public DogCounts { if (total < 0 || active < 0 || active > total) { throw new IllegalArgumentException("Invalid dog counts"); } }
    }
    public record Thresholds(int ok, int tight, int shortfall) {
        public Thresholds {
            if (shortfall < 0 || tight <= shortfall || ok <= tight) { throw new IllegalArgumentException("Invalid coverage thresholds"); }
        }
    }
    public enum Band { OK, TIGHT, SHORT, EXPAND, NO_ACTIVE_DOGS }
    public record Coverage(BigDecimal maximumPlaces, BigDecimal proportionalPlaces, BigDecimal maximumPercentOfAll,
                           BigDecimal proportionalPercentOfActive, Band band) { }

    public static Map<String, Coverage> coverage(List<ClassPlaces> classes, Map<String, DogCounts> dogs, Thresholds thresholds) {
        Map<String, Coverage> result = new LinkedHashMap<>();
        dogs.forEach((level, counts) -> {
            BigDecimal maximum = BigDecimal.ZERO;
            BigDecimal proportional = BigDecimal.ZERO;
            for (ClassPlaces session : classes) {
                if (session.levelIds().contains(level)) {
                    BigDecimal capacity = BigDecimal.valueOf(session.capacity());
                    maximum = maximum.add(capacity);
                    proportional = proportional.add(capacity.divide(BigDecimal.valueOf(session.levelIds().size()), 12, RoundingMode.HALF_UP));
                }
            }
            result.put(level, coverage(maximum, proportional, counts, thresholds));
        });
        return Collections.unmodifiableMap(result);
    }
    public static Coverage coverage(BigDecimal maximum, BigDecimal proportional, DogCounts dogs, Thresholds thresholds) {
        if (maximum.signum() < 0 || proportional.signum() < 0 || proportional.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid places");
        }
        BigDecimal activePercent = percent(proportional, dogs.active());
        BigDecimal placesPercent = proportional.multiply(BigDecimal.valueOf(100));
        BigDecimal active = BigDecimal.valueOf(dogs.active());
        Band band = activePercent == null ? Band.NO_ACTIVE_DOGS
                : placesPercent.compareTo(active.multiply(BigDecimal.valueOf(thresholds.ok()))) > 0 ? Band.OK
                : placesPercent.compareTo(active.multiply(BigDecimal.valueOf(thresholds.tight()))) >= 0 ? Band.TIGHT
                : placesPercent.compareTo(active.multiply(BigDecimal.valueOf(thresholds.shortfall()))) >= 0 ? Band.SHORT : Band.EXPAND;
        return new Coverage(maximum, proportional, percent(maximum, dogs.total()), activePercent, band);
    }
    private static BigDecimal percent(BigDecimal places, int dogs) {
        return dogs == 0 ? null : places.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(dogs), 8, RoundingMode.HALF_UP);
    }
}
