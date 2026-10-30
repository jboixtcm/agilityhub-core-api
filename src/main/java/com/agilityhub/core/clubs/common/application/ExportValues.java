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
    String accounting(Object value, String column) {
        if (value == null) { return ""; }
        java.math.BigDecimal number = null;
        if (Set.of("base", "tax", "total").contains(column) && value instanceof Map<?, ?> money && money.get("amountMinor") instanceof Number minor) {
            number = java.math.BigDecimal.valueOf(minor.longValue(), 2);
        } else if (column.equals("taxPercent")) { number = new java.math.BigDecimal(value.toString()); }
        // The receipt's status reads as on the receipt itself (ReceiptPdf): «Remesat», «Cobrat»…
        else if (column.equals("status")) { return messages.getMessage("billing.receipt.status." + value, null, value.toString(), locale); }
        if (number == null) { return text(value); }
        var format = java.text.NumberFormat.getNumberInstance(locale); format.setGroupingUsed(false);
        format.setMinimumFractionDigits(2); format.setMaximumFractionDigits(2); return format.format(number);
    }
    String census(Object value, String column) {
        if (value instanceof List<?> list && column.equals("dogs")) {
            return String.join("; ", list.stream().map(dog -> {
                if (!(dog instanceof Map<?, ?> item)) return text(dog);
                String name = text(item.get("name"));
                String level = item.get("level") instanceof Map<?, ?> ref ? text(ref.get("code")) : "";
                return name + (level.isEmpty() ? "" : " (" + level + ")");
            }).toList());
        }
        if (!(value instanceof Map<?, ?> map)) return text(value);
        return switch (column) {
            case "plan", "familyGroup", "level" -> text(map.get("name"));
            case "owner" -> text(map.get("fullName"));
            case "displayStatus" -> text(map.get("kind")) + (map.get("date") == null ? "" : " · " + text(map.get("date")));
            case "imageRights" -> text(map.get("granted"));
            case "freeTraining" -> text(map.get("allowed"));
            case "paymentMethod" -> join(map.get("type"), map.get("channel"), map.get("maskedAccount"));
            case "contact" -> {
                var parts = new ArrayList<String>();
                if (map.get("emails") instanceof List<?> emails) for (Object item : emails) {
                    if (item instanceof Map<?, ?> email) parts.add(text(email.get("email")));
                }
                if (map.get("phones") instanceof List<?> phones) for (Object item : phones) {
                    if (item instanceof Map<?, ?> phone) parts.add(text(phone.get("prefix")) + " " + text(phone.get("number")));
                }
                yield String.join("; ", parts);
            }
            default -> text(value);
        };
    }
    private String join(Object... values) {
        return String.join(" · ", Arrays.stream(values).map(this::text).filter(value -> !value.isEmpty()).toList());
    }
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
