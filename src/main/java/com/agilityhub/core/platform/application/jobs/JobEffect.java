package com.agilityhub.core.platform.application.jobs;

import java.util.Map;

/** What `apply` did to one item: the traced action and the counters it adds to `effects.counters`. */
public record JobEffect(String action, Map<String, Object> detail, Map<String, Long> counters) {
    public JobEffect {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
        counters = counters == null ? Map.of() : Map.copyOf(counters);
    }
    public static JobEffect of(String action, String counter) { return new JobEffect(action, Map.of(), Map.of(counter, 1L)); }
}
