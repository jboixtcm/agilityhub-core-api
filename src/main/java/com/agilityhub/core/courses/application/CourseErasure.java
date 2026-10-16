package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.persistence.CourseErasureRepository;
import com.agilityhub.core.platform.application.privacy.AccountErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasurePlaceholders;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** S16 §7 `AccountErased` → `Course.ownerType ACCOUNT` → `PRIVATE`, `designerName` null (S14 R-14-15, done in the erasure's transaction). */
@Component
public class CourseErasure implements AccountErasureStep {
    private final CourseErasureRepository courses;
    public CourseErasure(CourseErasureRepository courses) { this.courses = courses; }
    @Override public ErasureEffect eraseAccount(String accountId, String pseudonym, ErasurePlaceholders placeholders, Instant at) {
        return ErasureEffect.builder().count("courses", courses.eraseOwnedBy(accountId)).build();
    }
}
