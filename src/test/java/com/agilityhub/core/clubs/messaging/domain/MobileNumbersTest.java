package com.agilityhub.core.clubs.messaging.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * COMM-06 (owner decision of 13 Nov 2026, S11 R-11-06, T-11-06): an SMS goes only to a mobile number — Spanish numbers by
 * their 6/7 prefix, the others by libphonenumber's number type. Fictional numbers only.
 */
class MobileNumbersTest {
    @Test void T_11_06_COMM_06_spanishNumbersAreMobilesWhenTheyStartWithSixOrSeven() {
        assertThat(MobileNumbers.mobile("+34600000001")).isTrue();
        assertThat(MobileNumbers.mobile("+34712345678")).isTrue();
        assertThat(MobileNumbers.mobile(" +34612345678 ")).isTrue();
        // Landlines (9…, 8…), a service number (900…) and a number of the wrong length are not.
        assertThat(MobileNumbers.mobile("+34931234567")).isFalse();
        assertThat(MobileNumbers.mobile("+34812345678")).isFalse();
        assertThat(MobileNumbers.mobile("+34900123456")).isFalse();
        assertThat(MobileNumbers.mobile("+3461234567")).isFalse();
        assertThat(MobileNumbers.mobile("+346123456789")).isFalse();
    }

    @Test void T_11_06_COMM_06_otherCountriesFollowTheNumberTypeAndAnythingElseIsNoMobile() {
        assertThat(MobileNumbers.mobile("+33612345678")).as("French mobile").isTrue();
        assertThat(MobileNumbers.mobile("+447400123456")).as("British mobile").isTrue();
        assertThat(MobileNumbers.mobile("+12015550123")).as("North America: fixed line or mobile, not known as a landline").isTrue();
        assertThat(MobileNumbers.mobile("+33123456789")).as("French landline").isFalse();
        assertThat(MobileNumbers.mobile("+441212345678")).as("British landline").isFalse();
        assertThat(MobileNumbers.mobile("+18002345678")).as("toll-free").isFalse();
        for (String invalid : new String[] {null, "", "612345678", "+0123456789", "+34 612 345 678", "+1e9", "+999123456"}) {
            assertThat(MobileNumbers.mobile(invalid)).as(String.valueOf(invalid)).isFalse();
        }
    }
}
