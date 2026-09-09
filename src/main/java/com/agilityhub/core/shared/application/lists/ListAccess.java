package com.agilityhub.core.shared.application.lists;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rechecked in application entry points, including callers outside MVC. */
public final class ListAccess {
    private ListAccess() { }
    public static boolean admin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
    public static String account() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp")) || auth.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_INSTRUCTOR"))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return jwt.getSubject();
    }
}
