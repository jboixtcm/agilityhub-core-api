package com.agilityhub.core.clubs.bookings.application.ports;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local/test stand-in for S13 inactivity periods (E8 replaces it); tests and the demo seed register periods per club and member. */
@Component
@Profile({"local", "test"})
public class InMemoryInactivity implements InactivityPort {
    private final Map<String, List<Period>> periods = new ConcurrentHashMap<>();
    public void approve(String memberId, LocalDate from, LocalDate to) {
        periods.computeIfAbsent(key(memberId), k -> Collections.synchronizedList(new ArrayList<>())).add(new Period(from, to));
    }
    public void clear() { periods.clear(); }
    @Override public Optional<Period> covering(String memberId, LocalDate date) {
        return List.copyOf(periods.getOrDefault(key(memberId), List.of())).stream()
                .filter(p -> !date.isBefore(p.from()) && (p.to() == null || !date.isAfter(p.to()))).findFirst();
    }
    private static String key(String memberId) { return TenantContext.require() + ":" + memberId; }
}
