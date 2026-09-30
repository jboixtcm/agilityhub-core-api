package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.domain.ChannelResolver;
import com.agilityhub.core.shared.application.NotificationAccounts;
import java.util.ArrayList;
import java.util.List;

/**
 * R-11-08 with decision E12: the two bounce marks coexist and either one suppresses an address. A member's contact address
 * is written to only when neither its own `bounced` mark (a hard bounce of an engine e-mail) nor the account's `emailStatus`
 * (a bounce or complaint of a SYSTEM e-mail, E1-T03, on the account's login address) says so. The engine's first resolution,
 * the dispatcher's forced e-mail at the SMS cap and the dispatcher's check before every e-mail attempt (E7-T05) use this one
 * check.
 */
final class EmailSuppression {
    private EmailSuppression() { }

    /** The member's contact addresses, each marked `bounced` when either mark suppresses it. */
    static List<ChannelResolver.EmailAddress> addresses(MemberContact person, NotificationAccounts.Recipient account) {
        var addresses = new ArrayList<ChannelResolver.EmailAddress>();
        for (var address : person.emails()) {
            addresses.add(new ChannelResolver.EmailAddress(address.address(), address.bounced() || accountMarks(account, address.address())));
        }
        return addresses;
    }

    /**
     * R-11-08 before an attempt to `address` (compared without case): suppressed when one of the `holders` (the recipient's
     * member contact, or the members that have the address) marks it `bounced`, or the account's `emailStatus` marks it as
     * its login address.
     */
    static boolean suppressed(String address, List<MemberContact> holders, NotificationAccounts.Recipient account) {
        if (address == null || address.isBlank()) { return false; }
        if (accountMarks(account, address)) { return true; }
        String target = address.strip();
        return holders.stream().flatMap(holder -> holder.emails().stream()).anyMatch(email -> email.bounced() && email.address().strip().equalsIgnoreCase(target));
    }

    private static boolean accountMarks(NotificationAccounts.Recipient account, String address) {
        return account != null && account.emailStatus() != null && account.email() != null && account.email().equalsIgnoreCase(address.strip());
    }
}
