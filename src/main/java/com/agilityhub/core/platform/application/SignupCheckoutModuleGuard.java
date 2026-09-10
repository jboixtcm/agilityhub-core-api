package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Service;

@Service
public class SignupCheckoutModuleGuard implements SignupCheckoutGuard {
    private final ClubConfigService configs;
    public SignupCheckoutModuleGuard(ClubConfigService configs) { this.configs=configs; }
    @Override public void requireBilling() {
        if(!configs.get(TenantContext.require()).modules().contains(Module.BILLING)) throw new ApiException(ErrorCode.MODULE_DISABLED);
    }
}
