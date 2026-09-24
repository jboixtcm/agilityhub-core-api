package com.agilityhub.core.clubs.signup.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class EmailRules {
    private EmailRules() { }
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9!#$%&'*+/=?^_\\x60{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_\\x60{|}~-]+)*"
            + "@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");
    public record ContactEmail(String email, boolean primary, boolean bounced) { }
    /**
     * R-04-05 «m•••a@e•••.cat»: the first and last characters of the local part, the first of the domain and its last
     * label. A one-character local part keeps only that character; a domain without a dot keeps only its first one.
     */
    public static String masked(String email) {
        int at = email.lastIndexOf('@');
        String local = email.substring(0, Math.max(at, 0)), domain = email.substring(at + 1);
        if (local.isEmpty() || domain.isEmpty()) { return "•••"; }
        String head = local.length() == 1 ? local + "•••" : local.charAt(0) + "•••" + local.charAt(local.length() - 1);
        int dot = domain.lastIndexOf('.');
        return head + "@" + domain.charAt(0) + "•••" + (dot > 0 ? domain.substring(dot) : "");
    }
    public static List<ContactEmail> normalize(List<String> emails) {
        if (emails == null || emails.isEmpty() || emails.size() > 2) { throw SignupValidation.field("emails"); }
        var result = new ArrayList<ContactEmail>(); var distinct = new HashSet<String>();
        for (int i = 0; i < emails.size(); i++) {
            String email = emails.get(i) == null ? "" : emails.get(i).strip().toLowerCase(Locale.ROOT);
            if (email.length() > 254 || !EMAIL.matcher(email).matches() || !distinct.add(email)) {
                throw SignupValidation.field("emails[" + i + "]");
            }
            result.add(new ContactEmail(email, i == 0, false));
        }
        return List.copyOf(result);
    }
}
