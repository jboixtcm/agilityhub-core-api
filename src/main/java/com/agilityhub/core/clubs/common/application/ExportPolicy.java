package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.shared.application.lists.ListAccess;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ExportPolicy {
    public void requireAllowed(String format) {
        ListAccess.account();
        if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        if (!Set.of("xlsx", "pdf").contains(format)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
    /** Defence in depth for future providers; renderers only receive this masked copy. */
    public Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            var safe = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                String field = key.toString();
                if (field.equalsIgnoreCase("iban")) {
                    String text = Objects.toString(item, "");
                    safe.put("maskedAccount", text.isEmpty() ? "" : "···· ···· ···· ···· " + text.substring(Math.max(0, text.length() - 4)));
                } else { safe.put(field, mask(item)); }
            });
            return safe;
        }
        if (value instanceof List<?> list) { return list.stream().map(this::mask).toList(); }
        return value;
    }
}
