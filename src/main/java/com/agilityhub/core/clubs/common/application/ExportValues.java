package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.Money;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ExportValues {
    private final IcuMessageSource messages; private final Locale locale; private final ZoneId zone;
    ExportValues(IcuMessageSource messages, Locale locale, ZoneId zone) { this.messages = messages; this.locale = locale; this.zone = zone; }
    String label(String key, String column) { return messages.getMessage("export.column." + key + "." + column, null, column, locale); }
    String text(Object value) {
        if (value == null || value instanceof com.fasterxml.jackson.databind.node.NullNode) { return ""; }
        if (value instanceof Boolean flag) { return messages.getMessage("export.boolean." + flag, null, locale); }
        if (value instanceof Map<?, ?> map) {
            if (map.get("amountMinor") instanceof Number number && map.get("currency") instanceof String currency) { return new Money(number.longValue(), currency).format(locale); }
            if (map.containsKey("values")) { return text(map.get("values")); }
            // A qualified value (E7-T03 round 2: a notification's channel and its delivery status) reads «App (Lliurat)».
            if (map.size() == 2 && map.containsKey("value") && map.containsKey("qualifier")) { return text(map.get("value")) + " (" + text(map.get("qualifier")) + ")"; }
            if (map.containsKey(locale.getLanguage())) { return text(map.get(locale.getLanguage())); }
            return String.join("; ", map.values().stream().map(this::text).filter(item -> !item.isEmpty()).toList());
        }
        if (value instanceof List<?> list) { return String.join("; ", list.stream().map(this::text).toList()); }
        String text = value.toString();
        try {
            if (text.matches("\\d{4}-\\d{2}-\\d{2}")) { return LocalDate.parse(text).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")); }
            if (text.matches("\\d{4}-\\d{2}-\\d{2}T.*")) { return DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(zone).format(Instant.parse(text)); }
        } catch (java.time.format.DateTimeParseException ignored) { /* Preserve ordinary text that resembles a date. */ }
        return messages.getMessage("export.value." + text, null, text, locale);
    }
}
