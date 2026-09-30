package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * `bin/core checkout:fake-provider <completion|expiry> <checkoutSessionId> --club=<slug> [--paid-at=<instant>]`: the fake
 * provider's side of a checkout opened on a live local stack (E5-T30 round 2: the checkout steps of `bin/e3-smoke`). It calls
 * the webhook's handler ({@link CheckoutService#complete} / {@link CheckoutService#expire}) as {@link FakeCheckoutGateway}'s
 * `complete` / `expire` do in the tests; `--paid-at` is when the provider took the payment (default: now). Only under the
 * fake's own profiles, never with `staging` or `prod`.
 */
@Component
@Profile("(local | test) & !staging & !prod")
public class FakeProviderCommand implements CoreCommand {
    static final String USAGE = "Usage: checkout:fake-provider <completion|expiry> <checkoutSessionId> --club=<slug> [--paid-at=<instant>]";
    private final ClubConfigService clubs; private final FakeCheckoutGateway fake;
    public FakeProviderCommand(ClubConfigService clubs, FakeCheckoutGateway fake) { this.clubs = clubs; this.fake = fake; }
    @Override public String name() { return "checkout:fake-provider"; }
    @Override public void run(ApplicationArguments args) {
        var positional = args.getNonOptionArgs(); var club = args.getOptionValues("club"); var paid = args.getOptionValues("paid-at");
        if (positional.size() != 2 || !Set.of("completion", "expiry").contains(positional.getFirst()) || club == null || club.size() != 1
                || paid != null && (paid.size() != 1 || !"completion".equals(positional.getFirst()))
                || !Set.of("core.command", "club", "paid-at").containsAll(args.getOptionNames())) {
            throw new IllegalArgumentException(USAGE);
        }
        Instant paidAt;
        try { paidAt = paid == null ? null : Instant.parse(paid.getFirst()); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException(USAGE); }
        String clubId = clubs.findClubIdBySlug(club.getFirst()).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        String session = positional.get(1);
        if ("completion".equals(positional.getFirst())) { fake.completion(clubId, session, paidAt); } else { fake.expiry(clubId, session); }
        System.out.println("Fake provider " + positional.getFirst() + " delivered: checkoutSessionId=" + session);
    }
}
