package com.agilityhub.core.platform.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public class ParameterValidator {
    public void validate(ParameterDefinition definition, Object value, String defaultLocale, String currency) {
        try {
            require(value != null);
            switch (definition.type()) {
                case "int", "duration" -> require(integer(value));
                case "decimal" -> require(value instanceof Number && Double.isFinite(((Number) value).doubleValue()));
                case "bool" -> require(value instanceof Boolean);
                case "time" -> time(value);
                case "string" -> require(value instanceof String);
                case "enum" -> require(((List<?>) definition.constraints().get("enum")).contains(value));
                case "localizedText" -> localized(value, defaultLocale);
                case "money" -> {
                    Map<?, ?> money = (Map<?, ?>) value;
                    require(integer(money.get("amountMinor")) && ((Number) money.get("amountMinor")).longValue() >= 0);
                    require(currency.equals(money.get("currency")));
                }
                case "list" -> require(value instanceof List<?>);
                case "json" -> require(value instanceof Map<?, ?> || value instanceof List<?> || value instanceof Number);
                default -> throw new IllegalArgumentException("Unsupported catalog type");
            }
            if (value instanceof Number number) {
                double minimum = ((Number) definition.constraints().getOrDefault("min", -Double.MAX_VALUE)).doubleValue();
                double maximum = ((Number) definition.constraints().getOrDefault("max", Double.MAX_VALUE)).doubleValue();
                require(Double.isFinite(number.doubleValue()) && number.doubleValue() >= minimum && number.doubleValue() <= maximum);
            }
            Object pattern = definition.constraints().get("pattern");
            if (pattern != null) { require(value instanceof String text && text.matches(pattern.toString())); }
            String validator = (String) definition.constraints().getOrDefault("validator", "");
            switch (validator) {
                case "weekOpensAt" -> {
                    Map<?, ?> week = (Map<?, ?>) value;
                    DayOfWeek.valueOf((String) week.get("dayOfWeek")); time(week.get("time"));
                }
                case "coverageThresholds" -> {
                    Map<?, ?> thresholds = (Map<?, ?>) value;
                    double ok = ((Number) thresholds.get("ok")).doubleValue();
                    double tight = ((Number) thresholds.get("tight")).doubleValue();
                    double shortValue = ((Number) thresholds.get("short")).doubleValue();
                    require(Double.isFinite(ok) && ok > tight && tight > shortValue && shortValue >= 0);
                }
                case "openingHours" -> ((Map<?, ?>) value).forEach((day, hours) -> {
                    DayOfWeek.valueOf((String) day);
                    Map<?, ?> range = (Map<?, ?>) hours;
                    LocalTime open = time(range.get("open")), close = time(range.get("close"));
                    require(open.isBefore(close) && open.getMinute() % 5 == 0 && close.getMinute() % 5 == 0);
                });
                case "holidays" -> {
                    for (Object holiday : (List<?>) value) {
                        if (holiday instanceof String date) { LocalDate.parse(date); }
                        else { Map<?, ?> item = (Map<?, ?>) holiday; LocalDate.parse((String) item.get("date"));
                            require(item.get("label") instanceof String); }
                    }
                }
                case "localizedList" -> ((List<?>) value).forEach(item -> localized(item, defaultLocale));
                case "labeledKeys", "documentTypes" -> {
                    var keys = new java.util.HashSet<String>();
                    for (Object item : (List<?>) value) {
                        Map<?, ?> entry = (Map<?, ?>) item;
                        require(entry.get("key") instanceof String key && !key.isBlank() && keys.add(key));
                        localized(entry.get("label"), defaultLocale);
                        if (validator.equals("documentTypes")) { require(entry.get("required") instanceof Boolean); }
                    }
                }
                case "decimal" -> require(value instanceof Number);
                case "" -> { }
                default -> throw new IllegalArgumentException("Unsupported catalog validator");
            }
        } catch (RuntimeException invalid) {
            throw new ApiException(ErrorCode.PARAMETER_INVALID, Map.of("key", definition.key(), "type", definition.type()));
        }
    }
    private static boolean integer(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
    }
    private static LocalTime time(Object value) {
        require(value instanceof String text && text.matches("[0-2][0-9]:[0-5][0-9]"));
        return LocalTime.parse((String) value);
    }
    private static void localized(Object value, String locale) {
        Map<?, ?> text = (Map<?, ?>) value;
        require(text.get(locale) instanceof String translation && !translation.isBlank());
        require(text.entrySet().stream().allMatch(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String));
    }
    private static void require(boolean valid) { if (!valid) { throw new IllegalArgumentException("Invalid parameter"); } }
}
