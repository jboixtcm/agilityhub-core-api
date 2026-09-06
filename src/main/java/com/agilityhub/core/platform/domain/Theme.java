package com.agilityhub.core.platform.domain;

import java.util.List;

public record Theme(String logoUrl, String logoDarkUrl, String markUrl, Colors colors,
                    String fontFamily, String radius, List<String> ringPalette, Mode mode) {
    public Theme { ringPalette = List.copyOf(ringPalette); }
    public record Colors(String primary, String onPrimary, String background, String surface,
                         String surfaceAlt, String text, String textMuted, String border,
                         String success, String warning, String danger, String info) { }
    public enum Mode { auto, light, dark }
}
