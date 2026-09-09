package com.agilityhub.core.clubs.content.domain;

import java.net.URI;
import java.util.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/** Validate the parsed syntax, including decoded/reference link destinations, without rewriting user text. */
public final class LimitedMarkdown {
    private static final Set<Class<?>> ALLOWED = Set.of(Document.class, Heading.class, Paragraph.class,
            Text.class, Emphasis.class, StrongEmphasis.class, BulletList.class, OrderedList.class,
            ListItem.class, Link.class, LinkReferenceDefinition.class, SoftLineBreak.class, HardLineBreak.class);
    private LimitedMarkdown() { }
    public static void validate(String markdown, String field) {
        var pending = new ArrayDeque<Node>(); pending.push(Parser.builder().build().parse(markdown));
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!ALLOWED.contains(node.getClass())) { throw PageContent.invalid(field); }
            if (node instanceof Link link) { destination(link.getDestination(), field); }
            if (node instanceof LinkReferenceDefinition link) { destination(link.getDestination(), field); }
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) { pending.push(child); }
        }
    }
    private static void destination(String value, String field) {
        try {
            if (value.chars().anyMatch(c -> Character.isISOControl(c) || c == '\\') || value.startsWith("//")) { throw new IllegalArgumentException(); }
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme != null && !Set.of("http", "https", "mailto").contains(scheme.toLowerCase(Locale.ROOT))) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException invalid) { throw PageContent.invalid(field); }
    }
}
