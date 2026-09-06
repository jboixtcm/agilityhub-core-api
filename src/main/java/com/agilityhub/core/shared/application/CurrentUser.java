package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.DomainEvent;

/** Request-scoped identity for application services; populated only after bearer/tenant validation. */
public record CurrentUser(String accountId, String name, Impersonation impersonation, DomainEvent.Origin origin) {
    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();
    public static CurrentUser current() { return CURRENT.get(); }
    public static Scope open(CurrentUser user) {
        CurrentUser previous = CURRENT.get();
        CURRENT.set(user);
        return () -> { if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); } };
    }
    public record Impersonation(String actorAccountId, String actorName, String memberId) { }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
