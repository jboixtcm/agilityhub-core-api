package com.agilityhub.core.clubs.catalogs.persistence;

import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INC-74 (E11-T13): {@link PriceRepository}'s two startup helpers read and write every club (no tenant, AGENTS rule 4's documented
 * exception): only {@link CatalogPersistence}'s runner, in the same package, may call them.
 */
class PriceStartupHelpersTest {
    @Test void INC_74_theCrossTenantStartupHelpersAreNotPublic() throws Exception {
        for (String helper : List.of("normaliseValidityDates", "legacyStartTwins")) {
            int modifiers = PriceRepository.class.getDeclaredMethod(helper).getModifiers();
            assertThat(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)).as(helper).isFalse();
        }
    }
}
