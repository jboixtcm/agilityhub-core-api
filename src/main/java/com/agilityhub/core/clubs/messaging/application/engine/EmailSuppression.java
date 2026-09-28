package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.domain.ChannelResolver;
import com.agilityhub.core.shared.application.NotificationAccounts;
import java.util.ArrayList;
import java.util.List;

/**
 * R-11-08 with decision E12: the two bounce marks coexist and either one suppresses an address. A member's contact address
 * is written to only when neither its own `bounced` mark (a hard bounce of an engine e-mail) nor the account's `emailStatus`
 * (a bounce or complaint of a SYSTEM e-mail, E1-T03, on the account's login address) says so. The engine's first resolution
 * and the dispatcher's forced e-mail at the SMS cap use this one check.
 */
final class EmailSuppression {
    private EmailSuppression() { }

    /** The member's contact addresses, each marked `bounced` when either mark suppresses it. */
    static List<ChannelResolver.EmailAddress> addresses(MemberContact person, NotificationAccounts.Recipient account) {
        var addresses = new ArrayList<ChannelResolver.EmailAddress>();
        for (var address : person.emails()) {
            boolean accountBounced = account != null && account.emailStatus() != null && account.email() != null && account.email().equalsIgnoreCase(address.address().strip());
            addresses.add(new ChannelResolver.EmailAddress(address.address(), address.bounced() || accountBounced));
        }
        return addresses;
    }
}
