package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.springframework.stereotype.Service;

/** Services and schedulers call this before any module-specific work. */
@Service
public class ModuleGuard {
    private final ClubConfigService configs;

    public ModuleGuard(ClubConfigService configs) { this.configs = configs; }

    public void require(String clubId, Module module) {
        if (!configs.get(clubId).modules().contains(module)) {
            throw new ApiException(ErrorCode.MODULE_DISABLED);
        }
    }
}
