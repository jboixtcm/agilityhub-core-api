package com.agilityhub.core.identity.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.stereotype.Component;

@Component
public class AuthSettings {
    private final ClubConfigService clubs;
    private final ParameterCatalog catalog;
    public AuthSettings(ClubConfigService clubs, ParameterCatalog catalog) { this.clubs = clubs; this.catalog = catalog; }
    public int integer(String key) { return ((Number) value(key)).intValue(); }
    public boolean enabled(String key) { return Boolean.TRUE.equals(value(key)); }
    public void requireClubAccess() {
        if (clubs.get(TenantContext.require()).club().status().equals("SUSPENDED")) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.CLUB_SUSPENDED);
        }
    }
    private Object value(String key) {
        return TenantContext.current() == null ? catalog.defaultValue(key) : clubs.get(TenantContext.require()).get(key, Object.class);
    }
}
