package com.agilityhub.core.clubs.activities.domain;

import org.owasp.html.*;

/** Explicit S07 allowlist; parser-based sanitization also normalizes malformed HTML. */
public final class HtmlSanitizer {
    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em", "u", "s", "h3", "h4", "ul", "ol", "li", "blockquote")
            .allowElements((name, attributes) -> "strong", "b")
            .allowElements((name, attributes) -> "em", "i")
            .allowElements((name, attributes) -> {
                if (!attributes.contains("href")) return null;
                attributes.add("rel"); attributes.add("noopener"); attributes.add("target"); attributes.add("_blank"); return "a";
            }, "a")
            .allowAttributes("href").matching(java.util.regex.Pattern.compile("(?i)^(?:https?://|mailto:).+" )).onElements("a").skipRelsOnLinks("noreferrer").allowUrlProtocols("http", "https", "mailto").toFactory();
    private HtmlSanitizer() { }
    public static String sanitize(String html) { return html == null ? null : POLICY.sanitize(html); }
    public static String text(String html) {
        if (html == null) return null;
        StringBuilder plain = new StringBuilder();
        org.owasp.html.HtmlSanitizer.sanitize(sanitize(html), new org.owasp.html.HtmlSanitizer.Policy() {
            public void openDocument() { } public void closeDocument() { }
            public void openTag(String name, java.util.List<String> attrs) { plain.append(' '); }
            public void closeTag(String name) { plain.append(' '); }
            public void text(String value) { plain.append(value); }
        });
        String result = plain.toString().replaceAll("\\s+", " ").trim();
        return result.substring(0, Math.min(500, result.length()));
    }
}
