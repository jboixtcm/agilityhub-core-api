package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * `bin/core attendance:claim-no-show [--club=<slug>]`: runs the R-10-06 claim for each active club (or one) with the
 * club-local date of today, as the P3 job of E6-T04 will at `messaging.noShowNoticeTime`.
 */
@Component
public class ClaimNoShowCommand implements CoreCommand {
    private final ClubConfigService clubs; private final NoShowNoticeClaims claims;
    public ClaimNoShowCommand(ClubConfigService clubs, NoShowNoticeClaims claims) { this.clubs = clubs; this.claims = claims; }
    public String name() { return "attendance:claim-no-show"; }
    public void run(ApplicationArguments args) {
        var values = args.getOptionValues("club");
        if (!args.getNonOptionArgs().isEmpty() || !Set.of("core.command", "club").containsAll(args.getOptionNames()) || values != null && values.size() != 1) {
            throw new IllegalArgumentException("Usage: attendance:claim-no-show [--club=<slug>]");
        }
        var ids = values == null ? clubs.activeClubIds()
                : List.of(clubs.findClubIdBySlug(values.getFirst()).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND)));
        for (String id : ids) {
            try (var tenant = TenantContext.open(id)) {
                var claim = claims.claim(claims.today());
                System.out.println("No-show notices claimed: " + claim.bookingIds().size() + (claim.eventId() == null ? "" : " (NoShowNoticeDue " + claim.eventId() + ")"));
            }
        }
    }
}
