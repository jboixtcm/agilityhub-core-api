package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;

/** Request scope is immutable; background workers must explicitly open a trusted scope. */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private TenantContext() { }
    public static String current() { return CURRENT.get(); }
    public static String require() {
        String clubId = current();
        if (clubId == null) { throw new ApiException(ErrorCode.NO_MEMBERSHIP); }
        return clubId;
    }
    public static Scope open(String clubId) {
        if (clubId == null || clubId.isBlank()) { throw new ApiException(ErrorCode.NO_MEMBERSHIP); }
        String previous = current();
        if (previous != null && !previous.equals(clubId)) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
        CURRENT.set(clubId);
        return () -> { if (previous == null) { clear(); } };
    }
    public static void clear() { CURRENT.remove(); }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
