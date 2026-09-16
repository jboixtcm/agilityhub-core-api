package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Service;

/** Read-only tenant checks for the remaining reserved S06 calendar operations. */
@Service
public class SchedulingContractAccess {
    private final WeekRepository weeks;
    private final ClassSessionRepository classes;
    private final RingBlockRepository blocks;
    public SchedulingContractAccess(WeekRepository weeks,
            ClassSessionRepository classes, RingBlockRepository blocks) {
        this.weeks = weeks; this.classes = classes; this.blocks = blocks;
    }
    public void tenant() { TenantContext.require(); }
    public void week(String id) { weeks.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public void classSession(String id) { classes.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public void block(String id) { blocks.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
}
