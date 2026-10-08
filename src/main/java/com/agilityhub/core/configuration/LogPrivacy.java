package com.agilityhub.core.configuration;

import java.util.List;
import java.util.regex.Pattern;

/** R-14-18: one exclusion list for console messages and error telemetry. No request body is collected. */
public final class LogPrivacy {
    private static final List<Pattern> EXCLUSIONS = List.of(
            Pattern.compile("(?i)[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}"),
            Pattern.compile("(?i)\\b[A-Z]{2}\\d{2}(?:[ -]?[A-Z0-9]){11,30}\\b"),
            Pattern.compile("(?<![\\w-])\\+?\\d(?:[ .()-]{0,2}\\d){8,14}(?![\\w-])"),
            Pattern.compile("(?<![\\w])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w])"),
            Pattern.compile("(?i)(?<![\\w])(?:(?:[a-f0-9]{1,4}:){7}[a-f0-9]{1,4}|(?:[a-f0-9]{1,4}:){0,6}[a-f0-9]{0,4}::(?:[a-f0-9]{1,4}:){0,6}[a-f0-9]{0,4})(?![\\w])"),
            Pattern.compile("(?i)(?:bearer\\s+|(?:token|password|secret|signature|authorization|cookie)[=:]\\s*)[^\\s,;]+"));
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private LogPrivacy() { }
    /** Trusted server-generated correlation ids must survive patterns resembling a phone or IBAN. */
    public static String identifier(String value) {
        return value != null && UUID.matcher(value).matches() ? value : scrub(value);
    }
    public static String scrub(String value) {
        if (value == null) { return null; }
        String result = value;
        for (Pattern pattern : EXCLUSIONS) { result = pattern.matcher(result).replaceAll("[redacted]"); }
        return result;
    }
    /** Exception messages may contain full Mongo documents or request bodies; preserve only type and code locations. */
    public static String stack(ch.qos.logback.classic.spi.IThrowableProxy error) {
        if (error == null) { return ""; }
        var result = new StringBuilder(error.getClassName()).append('\n');
        for (var frame : error.getStackTraceElementProxyArray()) { result.append("\t").append(frame).append('\n'); }
        if (error.getCause() != null && !error.getCause().isCyclic()) { result.append("Caused by: ").append(stack(error.getCause())); }
        return scrub(result.toString());
    }
}
