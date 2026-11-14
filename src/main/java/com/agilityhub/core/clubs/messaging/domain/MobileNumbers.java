package com.agilityhub.core.clubs.messaging.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * COMM-06 (owner decision of 13 Nov 2026, S11 R-11-06): an SMS goes only to a mobile number. A Spanish number (`+34`) is a
 * mobile when its nine digits start with 6 or 7; any other number must be a valid E.164 number that libphonenumber does not
 * know as anything but a mobile (`MOBILE`, or `FIXED_LINE_OR_MOBILE` where the numbering plan cannot tell them apart, as in
 * North America). A landline, a service number or anything unparsable is no mobile: its SMS is skipped and recorded.
 */
public final class MobileNumbers {
    /** The `lastError` of an SMS delivery to a number that is not a mobile (`SKIPPED_NO_CONTACT`, the number kept as its target). */
    public static final String NOT_MOBILE = "Not a mobile number";
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{6,14}");
    private static final Pattern SPANISH_MOBILE = Pattern.compile("\\+34[67]\\d{8}");
    private static final Set<PhoneNumberType> MOBILE_TYPES = Set.of(PhoneNumberType.MOBILE, PhoneNumberType.FIXED_LINE_OR_MOBILE);

    private MobileNumbers() { }

    public static boolean mobile(String number) {
        if (number == null) { return false; }
        String e164 = number.strip();
        if (!E164.matcher(e164).matches()) { return false; }
        if (e164.startsWith("+34")) { return SPANISH_MOBILE.matcher(e164).matches(); }
        var util = PhoneNumberUtil.getInstance();
        try {
            var parsed = util.parse(e164, "ZZ");
            return util.isValidNumber(parsed) && MOBILE_TYPES.contains(util.getNumberType(parsed));
        } catch (NumberParseException invalid) { return false; }
    }
}
