package com.agilityhub.core.platform.domain;

import com.agilityhub.core.shared.domain.*;
import java.util.*;

/** S17: AA contrast for ordinary text and the primary action label. */
public final class ConsoleThemeRules {
    private ConsoleThemeRules() { }
    public static void validate(Theme theme) {
        var c=theme.colors();
        if(c==null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        for(String color:Arrays.asList(c.primary(),c.onPrimary(),c.background(),c.surface(),c.surfaceAlt(),c.text(),
                c.textMuted(),c.border(),c.success(),c.warning(),c.danger(),c.info())) color(color);
        theme.ringPalette().forEach(ConsoleThemeRules::color);
        for(var pair:List.of(List.of(c.onPrimary(),c.primary()),List.of(c.text(),c.background()),List.of(c.text(),c.surface())))
            if(contrast(pair.get(0),pair.get(1))<4.5) throw new ApiException(ErrorCode.THEME_CONTRAST);
    }
    private static void color(String value) {
        if(value==null||!value.matches("#[a-fA-F0-9]{6}")) throw new ApiException(ErrorCode.VALIDATION_ERROR,Map.of("field","theme.colors"));
    }
    static double contrast(String a,String b) { double x=luminance(a),y=luminance(b);return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05); }
    private static double luminance(String color) {
        double result=0; double[] weights={0.2126,0.7152,0.0722};
        for(int i=0;i<3;i++) { double x=Integer.parseInt(color.substring(1+2*i,3+2*i),16)/255.0;
            result+=weights[i]*(x<=0.04045?x/12.92:Math.pow((x+0.055)/1.055,2.4)); }
        return result;
    }
}
