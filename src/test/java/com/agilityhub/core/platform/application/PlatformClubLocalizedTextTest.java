package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S17 §6 (E10-W02 Q4, E11-T19 step 5): a localized text's refusal names the nested path in `fieldErrors[]`. */
class PlatformClubLocalizedTextTest {
    static Object fieldErrors(Runnable call) {
        var refused = catchThrowableOfType(ApiException.class, call::run);
        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        return refused.details().get("fieldErrors");
    }

    @Test void T_17_02_R_17_02_localizedTextRefusalsNameTheNestedPath() {
        var locales = List.of("ca", "es");
        assertThat(fieldErrors(() -> PlatformClubChanges.localized(null, locales, "ca", "legal.imageConsentText")))
                .isEqualTo(List.of(Map.of("field", "legal.imageConsentText", "code", "REQUIRED")));
        assertThat(fieldErrors(() -> PlatformClubChanges.localized(Map.of("es", "Text"), locales, "ca", "legal.imageConsentText")))
                .isEqualTo(List.of(Map.of("field", "legal.imageConsentText.ca", "code", "REQUIRED")));
        assertThat(fieldErrors(() -> PlatformClubChanges.localized(Map.of("ca", ""), locales, "ca", "legal.imageConsentText")))
                .isEqualTo(List.of(Map.of("field", "legal.imageConsentText.ca", "code", "REQUIRED")));
        assertThat(fieldErrors(() -> PlatformClubChanges.localized(Map.of("ca", "Text", "fr", "Texte"), locales, "ca", "legal.imageConsentText")))
                .isEqualTo(List.of(Map.of("field", "legal.imageConsentText.fr", "code", "INVALID_VALUE")));
        assertThatCode(() -> PlatformClubChanges.localized(Map.of("ca", "Text", "es", "Texto"), locales, "ca", "legal.imageConsentText"))
                .doesNotThrowAnyException();
    }
}
