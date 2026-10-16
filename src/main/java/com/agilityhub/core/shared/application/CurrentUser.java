package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.DomainEvent;

/** Request-scoped identity for application services; populated only after bearer/tenant validation. */
public record CurrentUser(String accountId, String name, Impersonation impersonation, DomainEvent.Origin origin, Support support) {
    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();
    public CurrentUser(String accountId, String name, Impersonation impersonation, DomainEvent.Origin origin) {
        this(accountId, name, impersonation, origin, null);
    }
    public static CurrentUser current() { return CURRENT.get(); }
    public static Scope open(CurrentUser user) {
        CurrentUser previous = CURRENT.get();
        CURRENT.set(user);
        return () -> { if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); } };
    }
    public record Impersonation(String actorAccountId, String actorName, String memberId) { }
    /** S17 R-17-10 (E10-T04): a validated support access of the AgilityHub team; the platform account is the request's account. */
    public record Support(String grantId, String actorAccountId, String actorName, java.time.Instant expiresAt) { }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
