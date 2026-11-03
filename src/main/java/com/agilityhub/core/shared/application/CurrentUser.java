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
    public record Impersonation(String actorAccountId, String actorName, String memberId, boolean bookingsOnly) {
        public Impersonation(String actorAccountId, String actorName, String memberId) { this(actorAccountId, actorName, memberId, false); }
    }
    public static boolean bookingsOnly() {
        var user = current();
        return user != null && user.impersonation() != null && user.impersonation().bookingsOnly();
    }
    /** S17 R-17-10 (E10-T04): a validated support access of the AgilityHub team; the platform account is the request's account. */
    public record Support(String grantId, String actorAccountId, String actorName, java.time.Instant expiresAt) { }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
