package com.agilityhub.core.clubs.activities.domain;

import java.text.Normalizer;
/** GSM-7 basic alphabet only: accents are folded and an ASCII ellipsis uses three septets. */
public final class ActivitySms {
    private ActivitySms() { }
    public static String compact(String text) {
        String ascii=Normalizer.normalize(text.replace("…","...").replace("’","'"),Normalizer.Form.NFD).replaceAll("\\p{M}","")
                .replaceAll("[^A-Za-z0-9 @!\"#%&'()*+,./:;<=>?_$\\-\\r\\n]"," ");
        return ascii.length()<=160?ascii:ascii.substring(0,157)+"...";
    }
}
