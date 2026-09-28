package com.agilityhub.core.shared.application;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/** CONVENCIONS_API §5 (E5-T26; E5-T27 step 8, ruling E61): how the api answers a stored file on its own origin. */
public final class FileDownloads {
    private FileDownloads() { }

    /** The stored MIME type, or `application/octet-stream` when it does not parse. */
    public static MediaType type(String stored) {
        try { return MediaType.parseMediaType(stored); } catch (InvalidMediaTypeException invalid) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    /** An SVG can carry script: it is never shown on the api's origin, always an `attachment`. */
    public static boolean svg(MediaType type) { return "image".equals(type.getType()) && type.getSubtype().startsWith("svg"); }

    /** An image other than an SVG is shown (`inline`), so that an `<img>` of another origin displays it. */
    public static boolean shownInline(MediaType type) { return "image".equals(type.getType()) && !type.isWildcardSubtype() && !svg(type); }
}
