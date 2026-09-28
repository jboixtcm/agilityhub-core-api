package com.agilityhub.core.clubs.messaging.domain;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E7-T02 steps 6 and 8: the SMS text of R-11-06 (T-11-06), the R-11-09 backoff (T-11-09) and `dog_name_article` (R-11-05). */
class SmsTextTest {
    @Test void T_11_06_transliteratesToGsm7() {
        assertThat(SmsText.gsm7("Classe anul·lada — dimecres: «Pista mullada», l’app diu “sí”; ç ï í ó ú… 10 €"))
                .isEqualTo("Classe anul.lada - dimecres: \"Pista mullada\", l'app diu \"si\"; c i i o u... 10 EUR");
        // Letters of the GSM-7 set stay (è é à ò ñ ü); an accent outside it is dropped from the letter; a symbol without a form is dropped.
        assertThat(SmsText.gsm7("è é à ò ñ ü â ✓ → x")).isEqualTo("è é à ò ñ ü a  -> x");
        assertThat(SmsText.gsm7("a b c")).isEqualTo("a b c");
        var sms = SmsText.of("Demà: classe anul·lada a les 18:50 (C i sup.)", true);
        assertThat(sms.text()).isEqualTo("Demà: classe anul.lada a les 18:50 (C i sup.)"); // «à» is a GSM-7 letter assertThat(sms.truncated()).isFalse(); assertThat(sms.segments()).isEqualTo(1);
        // Without transliteration the accents stay (UCS-2: 70 characters per segment).
        var raw = SmsText.of("Demà: classe anul·lada", false);
        assertThat(raw.text()).isEqualTo("Demà: classe anul·lada"); assertThat(raw.segments()).isEqualTo(1);
        assertThat(new SmsText.Sms("·".repeat(71), false).segments()).isEqualTo(2);
    }

    @Test void T_11_06_cutsAt160WithAnEllipsisNeverSendsALinkAndCollapsesWhitespace() {
        String exact = "a".repeat(160), longer = "a".repeat(161);
        assertThat(SmsText.of(exact, true).text()).hasSize(160); assertThat(SmsText.of(exact, true).truncated()).isFalse();
        var cut = SmsText.of(longer, true);
        assertThat(cut.text()).hasSize(160).endsWith("..."); assertThat(cut.truncated()).isTrue(); assertThat(cut.segments()).isEqualTo(1);
        var unicode = SmsText.of("à".repeat(161), false);
        assertThat(unicode.text()).hasSize(160).endsWith("…"); assertThat(unicode.segments()).isEqualTo(3);
        // A long admin text: the SMS is cut, the full text goes by app and e-mail (the engine keeps both).
        String admin = "La classe queda anul·lada per la pluja. Podeu reservar-ne una altra des de l'app. Disculpeu les molèsties, us esperem la setmana vinent amb la pista seca!";
        assertThat(SmsText.of("Cànic: classe anul·lada dimecres 12 a les 18:50 (B+C). " + admin, true).text()).hasSize(160).endsWith("...");
        assertThat(SmsText.of("Entra a https://app.example.test/x o www.example.test ara", true).text()).isEqualTo("Entra a o ara");
        assertThat(SmsText.of("  una \t línia \n  i  una altra  ", true).text()).isEqualTo("una linia\ni una altra");
        assertThat(SmsText.of(null, true).text()).isEmpty();
        // A GSM-7 text longer than one SMS takes 153 characters per segment (the D9 preview's count).
        assertThat(new SmsText.Sms("a".repeat(161), true).segments()).isEqualTo(2);
    }

    @Test void T_11_09_backoffIsOneFiveFifteenSixtyMinutesAndTheFifthFailureIsFinal() {
        var now = Instant.parse("2026-10-07T10:00:00Z");
        assertThat(RetryPolicy.nextAttempt(1, true, now)).contains(now.plus(Duration.ofMinutes(1)));
        assertThat(RetryPolicy.nextAttempt(2, true, now)).contains(now.plus(Duration.ofMinutes(5)));
        assertThat(RetryPolicy.nextAttempt(3, true, now)).contains(now.plus(Duration.ofMinutes(15)));
        assertThat(RetryPolicy.nextAttempt(4, true, now)).contains(now.plus(Duration.ofMinutes(60)));
        assertThat(RetryPolicy.nextAttempt(5, true, now)).isEmpty();
        assertThat(RetryPolicy.BACKOFF.getLast()).isEqualTo(Duration.ofMinutes(240));
        assertThat(RetryPolicy.nextAttempt(1, false, now)).isEmpty();
        assertThatThrownBy(() -> RetryPolicy.nextAttempt(0, true, now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void R_11_05_dogNameArticleInCatalanOnly() {
        var ca = java.util.Locale.forLanguageTag("ca");
        assertThat(DogNameArticle.of("Duna", "FEMALE", ca)).isEqualTo("la Duna");
        assertThat(DogNameArticle.of("Rock", "MALE", ca)).isEqualTo("en Rock");
        assertThat(DogNameArticle.of("Ares", "MALE", ca)).isEqualTo("l'Ares");
        assertThat(DogNameArticle.of("Íria", "female", ca)).isEqualTo("l'Íria");
        assertThat(DogNameArticle.of("Hug", "MALE", ca)).isEqualTo("l'Hug");
        assertThat(DogNameArticle.of(" Duna ", null, ca)).isEqualTo("Duna");
        assertThat(DogNameArticle.of("Duna", "FEMALE", java.util.Locale.forLanguageTag("es"))).isEqualTo("Duna");
        assertThat(DogNameArticle.of("Rock", "MALE", java.util.Locale.ENGLISH)).isEqualTo("Rock");
        assertThat(DogNameArticle.of("Rock", "MALE", null)).isEqualTo("Rock");
        assertThat(DogNameArticle.of(" ", "MALE", ca)).isEmpty(); assertThat(DogNameArticle.of(null, "MALE", ca)).isEmpty();
        assertThat(DogNameArticle.of("H", "MALE", ca)).isEqualTo("en H");
    }

    @Test void R_11_09_deliveryStatusesTellWhatReachedAndWhatWasSkipped() {
        assertThat(DeliveryStatus.SENT.reached()).isTrue(); assertThat(DeliveryStatus.DELIVERED.reached()).isTrue(); assertThat(DeliveryStatus.QUEUED.reached()).isFalse();
        assertThat(DeliveryStatus.SKIPPED_CAP.skipped()).isTrue(); assertThat(DeliveryStatus.SKIPPED_NOT_ALLOWED.skipped()).isTrue(); assertThat(DeliveryStatus.FAILED.skipped()).isFalse();
    }
}
