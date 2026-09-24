package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.stereotype.Service;

/** Tenant guard of the S09 routes (resource ownership is {@link TrainingQueryService#visible}). */
@Service
public class TrainingContractAccess {
    public void tenant() { TenantContext.require(); }
}
