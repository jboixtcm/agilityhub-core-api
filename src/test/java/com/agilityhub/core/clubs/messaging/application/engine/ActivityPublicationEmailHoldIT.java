package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.platform.application.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Direct owner decision, 15 November: stop N-32a email for one club, retaining its APP notice and other email. */
class ActivityPublicationEmailHoldIT extends EngineFixtures {
    @BeforeEach void publicationOwner() {
        modules(CLUB, Module.ACTIVITIES, Module.SMS, Module.PUSH);
        modules(OTHER, Module.ACTIVITIES, Module.SMS, Module.PUSH);
        var withPublication = new ArrayList<>(owners);
        withPublication.add(new NotificationFactsPort() {
            public Set<String> eventTypes() { return Set.of("ActivityPublished"); }
            public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
                return Optional.of(NotificationFacts.builder().member("member-laura", null)
                        .value("activity_title", "Example activity").value("date", "2026-10-12")
                        .value("entityId", "example-activity").enable("EMAIL").build());
            }
        });
        owners = List.copyOf(withPublication);
        engine = engine(dispatcher);
    }

    @Test void T_11_03_publicationOptInIsHeldWithoutLosingTheAppNoticeOrReplayingAfterEnable() {
        dispatcher.setActivityPublicationEmailDisabledClubIds(Set.of(CLUB));
        String event = deliver(CLUB, "ActivityPublished", Map.of("notifyEmail", true));
        var notice = only(CLUB, "N-32a");
        assertThat(channels(notice)).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_NOT_ALLOWED");
        assertThat(notice.action().type()).isEqualTo(NotificationActionType.OPEN_ACTIVITY);
        assertThat(notice.deliveries()).filteredOn(d -> d.channel() == NotificationChannel.EMAIL).singleElement()
                .satisfies(d -> { assertThat(d.sentAt()).isNull(); assertThat(d.nextAttemptAt()).isNull(); assertThat(d.attempts()).isZero(); });
        assertThat(mail.sent).isEmpty();
        dispatcher.setActivityPublicationEmailDisabledClubIds(Set.of());
        deliver(event, CLUB, "ActivityPublished", Map.of("notifyEmail", true));
        assertThat(dispatcher.poll()).isZero();
        assertThat(mail.sent).isEmpty();
        assertThat(stored(CLUB, "N-32a")).hasSize(1);
    }

    @Test void T_11_09_alreadyQueuedPublicationEmailIsSkippedBeforeCallingTheProvider() {
        engine = engine(idleDispatcher());
        deliver(CLUB, "ActivityPublished", Map.of("notifyEmail", true));
        assertThat(channels(only(CLUB, "N-32a"))).containsExactly("APP:DELIVERED", "EMAIL:QUEUED");
        dispatcher.setActivityPublicationEmailDisabledClubIds(Set.of(CLUB));
        assertThat(dispatcher.poll()).isEqualTo(1);
        assertThat(channels(only(CLUB, "N-32a"))).containsExactly("APP:DELIVERED", "EMAIL:SKIPPED_NOT_ALLOWED");
        assertThat(mail.sent).isEmpty();
        dispatcher.setActivityPublicationEmailDisabledClubIds(Set.of());
        assertThat(dispatcher.poll()).isZero();
    }

    @Test void T_11_17_holdIsScopedToTheClubAndDoesNotSuppressOtherNotificationCodes() {
        dispatcher.setActivityPublicationEmailDisabledClubIds(Set.of(CLUB));
        deliver(OTHER, "ActivityPublished", Map.of("notifyEmail", true));
        assertThat(channels(only(OTHER, "N-32a"))).containsExactly("APP:DELIVERED", "EMAIL:SENT");
        int prior = mail.sent.size();
        classSession(CLUB, "e7t02-class-a", "2026-10-08T18:50", List.of("instructor-marta"));
        deliver(CLUB, "ClassAutoCancelled", Map.of("classId", "e7t02-class-a", "dogsCount", 1));
        assertThat(mail.sent.size()).isGreaterThan(prior);
        assertThat(stored(CLUB, "N-17")).allSatisfy(n -> assertThat(channels(n)).contains("EMAIL:SENT"));
    }

    @Test void T_11_03_defaultEmptyHoldPreservesPublicationEmailOptIn() {
        deliver(CLUB, "ActivityPublished", Map.of("notifyEmail", true));
        assertThat(channels(only(CLUB, "N-32a"))).containsExactly("APP:DELIVERED", "EMAIL:SENT");
        assertThat(mail.sent).hasSize(1);
    }
}
