package com.agilityhub.core.clubs.training.domain;

import java.text.Normalizer;

/** N-47 SMS body: GSM-7 safe characters only, at most 160 of them (same rule as the S06/S08 intents). */
public final class TrainingSms {
    private TrainingSms() { }
    public static String compact(String text) {
        String ascii = Normalizer.normalize(text.replace("…", "...").replace("’", "'").replace("–", "-"), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 @!\"#%&'()*+,./:;<=>?_$\\-\\r\\n]", " ");
        return ascii.length() <= 160 ? ascii : ascii.substring(0, 157) + "...";
    }
}
