package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * `bin/core billing:fake-webhook payment_intent.succeeded <paymentIntentId> --club=<slug>`:
 * the fake provider's webhook for a card charge on a live local stack (E8-T06's `bin/e8-smoke` without Stripe test keys). It
 * hands the event to the webhook's handler as {@link FakePaymentProvider#deliverWebhook} does in the tests; the signature
 * check is the real route's and is not exercised here. Only under the fake's own profiles, never with `staging` or `prod`.
 */
@Component
@Profile("(local | test) & !staging & !prod")
public class FakeWebhookCommand implements CoreCommand {
    static final String USAGE = "Usage: billing:fake-webhook payment_intent.succeeded <paymentIntentId> --club=<slug>";
    private final ClubConfigService clubs; private final FakePaymentProvider fake; private final Clock clock;
    public FakeWebhookCommand(ClubConfigService clubs, FakePaymentProvider fake, Clock clock) { this.clubs = clubs; this.fake = fake; this.clock = clock; }
    @Override public String name() { return "billing:fake-webhook"; }
    @Override public void run(ApplicationArguments args) {
        var positional = args.getNonOptionArgs(); var club = args.getOptionValues("club");
        if (positional.size() != 2 || !"payment_intent.succeeded".equals(positional.getFirst())
                || !positional.get(1).startsWith("pi_") || club == null || club.size() != 1 || !Set.of("core.command", "club").containsAll(args.getOptionNames())) {
            throw new IllegalArgumentException(USAGE);
        }
        String clubId = clubs.findClubIdBySlug(club.getFirst()).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        String type = positional.getFirst(), intent = positional.get(1);
        try (var tenant = TenantContext.open(clubId)) {
            fake.deliverWebhook(type, Map.of("eventId", "evt_fake_" + type.replace('.', '_') + "_" + intent, "created", clock.instant().getEpochSecond(), "object", Map.of("id", intent)));
        }
        System.out.println("Fake provider webhook delivered: " + type);
    }
}
