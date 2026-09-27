package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S11 §3 `MessageTemplate` (`message_templates`, D9): a `CATALOG` template has the `code` of its catalog row (unique per
 * club) and is born from the seed; a `CUSTOM` one has `code = null`. `smsBody` exists only when an SMS cell may be active.
 * `matrix` holds only the rows `MEMBER · INSTRUCTORS · ADMINS` and the columns `APP · EMAIL · SMS`: `APPLICANT` and `PUSH`
 * are fixed by the code (`NotificationSpec`). Nothing is deleted: a `CUSTOM` template is `ARCHIVED`.
 */
@Document("message_templates")
public record MessageTemplate(@Id String id, String clubId, String code, TemplateKind kind, NotificationCategory category, LocalizedText title,
        LocalizedText body, LocalizedText smsBody, TemplateIcon icon, TemplateColor color, Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix,
        boolean enabled, boolean mandatory, boolean customized, TemplateStatus status, @Version Long version, Instant createdAt, String createdBy,
        Instant updatedAt, String updatedBy) implements TenantEntity {
    public MessageTemplate {
        Objects.requireNonNull(kind); Objects.requireNonNull(category); Objects.requireNonNull(status);
        if ((kind == TemplateKind.CATALOG) == (code == null)) { throw new IllegalArgumentException("A CATALOG template has a code, a CUSTOM one none"); }
        if (!category.templated()) { throw new IllegalArgumentException("SYSTEM codes have no template (R-11-01)"); }
        var cells = new EnumMap<NotificationAudience, Map<NotificationChannel, Boolean>>(NotificationAudience.class);
        (matrix == null ? Map.<NotificationAudience, Map<NotificationChannel, Boolean>>of() : matrix).forEach((audience, row) -> {
            if (!audience.templated()) { throw new IllegalArgumentException("APPLICANT is no matrix row"); }
            var copy = new EnumMap<NotificationChannel, Boolean>(NotificationChannel.class);
            row.forEach((channel, active) -> {
                if (!channel.templated()) { throw new IllegalArgumentException("PUSH is no matrix column"); }
                copy.put(channel, Boolean.TRUE.equals(active));
            });
            cells.put(audience, Collections.unmodifiableMap(copy));
        });
        matrix = Collections.unmodifiableMap(cells);
    }
}
