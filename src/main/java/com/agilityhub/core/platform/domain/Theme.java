package com.agilityhub.core.platform.domain;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** E5-T16 (INC-08): a logo the club has not set is sent as `null` (`GET /club`, `/branding`), and declared nullable. */
public record Theme(@Schema(requiredMode = NOT_REQUIRED, nullable = true) String logoUrl,
                    @Schema(requiredMode = NOT_REQUIRED, nullable = true) String logoDarkUrl,
                    @Schema(requiredMode = NOT_REQUIRED, nullable = true) String markUrl, Colors colors,
                    String fontFamily, String radius, List<String> ringPalette, Mode mode) {
    public Theme { ringPalette = List.copyOf(ringPalette); }
    public record Colors(String primary, String onPrimary, String background, String surface,
                         String surfaceAlt, String text, String textMuted, String border,
                         String success, String warning, String danger, String info) { }
    public enum Mode { auto, light, dark }
}
