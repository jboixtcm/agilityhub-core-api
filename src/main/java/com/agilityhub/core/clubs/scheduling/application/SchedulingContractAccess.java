package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Service;

/** Read-only tenant checks for reserved S06 operations; mutations belong to E4-T02/T03. */
@Service
public class SchedulingContractAccess {
    private final WeekTemplateRepository templates;
    private final WeekRepository weeks;
    private final ClassSessionRepository classes;
    private final RingBlockRepository blocks;
    public SchedulingContractAccess(WeekTemplateRepository templates, WeekRepository weeks,
            ClassSessionRepository classes, RingBlockRepository blocks) {
        this.templates = templates; this.weeks = weeks; this.classes = classes; this.blocks = blocks;
    }
    public void tenant() { TenantContext.require(); }
    public void template(String id) { templates.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public void band(String id, String bandId) {
        var template = templates.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (template.timeBands().stream().noneMatch(b -> b.id().equals(bandId))) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    public void templateClass(String id, String classId) {
        var template = templates.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (template.classes().stream().noneMatch(c -> c.id().equals(classId))) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    public void week(String id) { weeks.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public void classSession(String id) { classes.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public void block(String id) { blocks.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public void coverage(String templateId, String saturdayTemplateId, String weekId) {
        if ((templateId == null) == (weekId == null) || weekId != null && saturdayTemplateId != null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (weekId != null) { week(weekId); }
        if (templateId != null) { template(templateId); }
        if (saturdayTemplateId != null) { template(saturdayTemplateId); }
    }
}
