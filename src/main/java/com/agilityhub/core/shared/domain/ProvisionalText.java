package com.agilityhub.core.shared.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** R-05-21b: one marker shared by club-definition text expansion and later exit checks. */
public final class ProvisionalText {
    public static final String MARKER = "[Text pendent — el club l'omplirà des de Paràmetres]";
    /**
     * PRIV-10: a value a draft leaves in brackets to be filled in (`[NOM DEL CLUB]`, `[07:00 a 22:00]`, `[DD-MM-AAAA]`). The text of
     * a Markdown link or image (`[text](url)`), a reference link (`[text][ref]`) or a link definition (`[ref]: url`) is not one.
     */
    private static final Pattern BRACKETED = Pattern.compile("(?<!\\])\\[([^\\[\\]\\n]{1,120})\\](?![(\\[:])");
    private ProvisionalText() { }

    /** PRIV-10: a placeholder body, nothing but {@link #MARKER} (what `${PROVISIONAL_TEXT}` expands to). */
    public static boolean placeholder(String text) { return text != null && text.strip().equals(MARKER); }

    /**
     * PRIV-10: the draft markers a text still holds, in order and once each: {@link #MARKER}, then every bracketed value to fill in.
     * Empty for a final text.
     */
    public static List<String> draftMarkers(String text) {
        var markers = new LinkedHashSet<String>();
        if (text == null) { return List.of(); }
        if (text.contains(MARKER)) { markers.add(MARKER); }
        var bracketed = BRACKETED.matcher(text.replace(MARKER, ""));
        while (bracketed.find()) { markers.add(bracketed.group()); }
        return new ArrayList<>(markers);
    }
}
