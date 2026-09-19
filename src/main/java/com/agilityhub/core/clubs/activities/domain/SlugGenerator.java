package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.*;
import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

public final class SlugGenerator {
    private SlugGenerator() { }
    public static String generate(String title, Predicate<String> exists) {
        String base = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\bd['’]", "").replace("·", "").replace("’", "")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.length() < 3) base = "activity-" + base;
        base = trim(base, 80);
        String slug = base;
        for (int n = 2; exists.test(slug); n++) {
            String suffix = "-" + n; slug = trim(base, 80 - suffix.length()) + suffix;
        }
        return slug;
    }
    private static String trim(String value, int max) { return value.substring(0, Math.min(max, value.length())).replaceAll("-+$", ""); }
    public static void editable(String before, String after, boolean everPublished) {
        if (everPublished && !before.equals(after)) throw new ApiException(ErrorCode.SLUG_LOCKED);
        if (after == null || after.length() < 3 || after.length() > 80 || !after.matches("[a-z0-9]+(-[a-z0-9]+)*"))
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
    }
}
