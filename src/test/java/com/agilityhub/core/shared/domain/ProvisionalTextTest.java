package com.agilityhub.core.shared.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** PRIV-10 (owner's decision of 13-11-2026; S05 R-05-21b): what makes a club text a placeholder or a draft. */
class ProvisionalTextTest {
    @Test void PRIV_10_aBodyMadeOnlyOfTheMarkerIsAPlaceholder() {
        assertThat(ProvisionalText.placeholder(ProvisionalText.MARKER)).isTrue();
        assertThat(ProvisionalText.placeholder("\n" + ProvisionalText.MARKER + "  \n")).isTrue();
        assertThat(ProvisionalText.placeholder(ProvisionalText.MARKER + "\n\n# Normes del club")).isFalse();
        assertThat(ProvisionalText.placeholder("Normes del club")).isFalse();
        assertThat(ProvisionalText.placeholder(null)).isFalse();
        assertThat(ProvisionalText.draftMarkers(ProvisionalText.MARKER)).containsExactly(ProvisionalText.MARKER);
    }

    @Test void PRIV_10_theMarkerAndTheBracketedValuesToFillInAreDraftMarkersOnceEach() {
        String draft = ProvisionalText.MARKER + "\n\n# Normes\n- El camp obre de **[07:00 a 22:00]** tots els dies, i el club és [NOM DEL CLUB].\n"
                + "- Fins a **[2] classes** la setmana; caduquen ([Pack 6: 3 mesos · Pack 10: 5 mesos]). [NOM DEL CLUB] ho revisa.";
        assertThat(ProvisionalText.draftMarkers(draft)).containsExactly(ProvisionalText.MARKER, "[07:00 a 22:00]", "[NOM DEL CLUB]", "[2]",
                "[Pack 6: 3 mesos · Pack 10: 5 mesos]");
    }

    @Test void PRIV_10_aFinalTextWithMarkdownLinksHasNoDraftMarkers() {
        String text = "# Normes del club\n\nConsulta la [política de privacitat](https://example.test/privacy), la [guia][guia] i ![el camp](https://example.test/camp.png).\n\n"
                + "[guia]: https://example.test/guia\n";
        assertThat(ProvisionalText.draftMarkers(text)).isEmpty();
        assertThat(ProvisionalText.draftMarkers(null)).isEmpty();
        assertThat(ProvisionalText.draftMarkers("")).isEmpty();
    }

    /** The seeded Catalan bodies of the Cànic's legal pages are drafts: each one starts with the marker and leaves values to fill in. */
    @Test void PRIV_10_theSeededLegalPagesAreDrafts() throws Exception {
        for (String page : new String[]{"rules", "image-consent", "privacy"}) {
            String body = Files.readString(Path.of("seeds/pages/" + page + ".ca.md")).replace("${PROVISIONAL_TEXT}", ProvisionalText.MARKER);
            assertThat(ProvisionalText.draftMarkers(body)).as(page).startsWith(ProvisionalText.MARKER).hasSizeGreaterThan(1);
            assertThat(ProvisionalText.placeholder(body)).as(page).isFalse();
        }
    }
}
