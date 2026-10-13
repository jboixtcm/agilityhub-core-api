package com.agilityhub.core.platform.domain;

import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConsoleThemeRulesTest {
    @Test void T_17_02_R_17_02_themeRequiresAaContrastAndValidHexTokens() {
        var theme=PlatformFixtures.club("example","example.test").theme();
        ConsoleThemeRules.validate(theme);
        assertThat(ConsoleThemeRules.contrast("#000000","#FFFFFF")).isEqualTo(21);
        assertThat(ConsoleThemeRules.contrast("#FFFFFF","#000000")).isEqualTo(21);
        assertThat(ConsoleThemeRules.contrast("#FFFFFF","#FFFFFF")).isEqualTo(1);
        var c=theme.colors();
        var bad=new Theme.Colors(c.primary(),c.primary(),c.background(),c.surface(),c.surfaceAlt(),c.text(),c.textMuted(),c.border(),c.success(),c.warning(),c.danger(),c.info());
        assertThatThrownBy(()->ConsoleThemeRules.validate(withColors(theme,bad))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.THEME_CONTRAST));
        assertThatThrownBy(()->ConsoleThemeRules.validate(withColors(theme,null))).isInstanceOf(ApiException.class);
        for(String invalid:new String[]{null,"#fff","red","#zzzzzz"}) {
            var malformed=new Theme.Colors(invalid,c.onPrimary(),c.background(),c.surface(),c.surfaceAlt(),c.text(),c.textMuted(),c.border(),c.success(),c.warning(),c.danger(),c.info());
            assertThatThrownBy(()->ConsoleThemeRules.validate(withColors(theme,malformed))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
    }
    private Theme withColors(Theme t,Theme.Colors c) {return new Theme(t.logoUrl(),t.logoDarkUrl(),t.markUrl(),c,t.fontFamily(),t.radius(),t.ringPalette(),t.mode());}
}
