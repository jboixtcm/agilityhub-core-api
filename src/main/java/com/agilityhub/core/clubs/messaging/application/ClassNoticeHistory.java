package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.ClassNoticeRepository;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ClassNoticeHistory {
    private final ClassNoticeRepository notices;
    private final ClubConfigService configs;
    private final Clock clock;
    public ClassNoticeHistory(ClassNoticeRepository notices, ClubConfigService configs, Clock clock) {
        this.notices = notices; this.configs = configs; this.clock = clock;
    }
    public record Item(String id, Instant createdAt, String code, String classId, String classDate, String classTime,
            String classDescription, String ringName, String title, String body) { }
    public record Page(List<Item> items, int page, int size, long totalItems) { }

    public Page page(boolean today, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ApiException(ErrorCode.VALIDATION_ERROR,
                Map.of("fieldErrors", List.of(Map.of("field", page < 0 ? "page" : "size", "code", "INVALID_VALUE"))));
        var zone = ZoneId.of(configs.get(TenantContext.require()).club().timeZone());
        var date = clock.instant().atZone(zone).toLocalDate();
        var result = notices.page(today ? date.atStartOfDay(zone).toInstant() : null,
                today ? date.plusDays(1).atStartOfDay(zone).toInstant() : null, page, size);
        return new Page(result.items().stream().map(n -> new Item(n.id(), n.createdAt(), n.code(), n.subject().classSessionId(),
                value(n, "class_date"), value(n, "class_time"), value(n, "class_description"), value(n, "ring_name"),
                Objects.toString(n.title(), ""), Objects.toString(n.body(), ""))).toList(), page, size, result.totalItems());
    }
    private static String value(Notification n, String key) { return n.variables() == null ? "" : Objects.toString(n.variables().get(key), ""); }
}
