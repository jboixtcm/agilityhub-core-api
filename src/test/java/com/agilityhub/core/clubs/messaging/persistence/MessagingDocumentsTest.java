package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.clubs.messaging.domain.*;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E7-T01 step 3: the invariants of the S11 §3 documents and the E1–E6 compatibility row (no database). */
class MessagingDocumentsTest {
    static final Instant NOW = Instant.parse("2026-09-28T07:00:00Z");

    @Test void WP_11_A_theE1RowIsOneS11DocumentWithASingleDeliveryAndItsFlatMirror() {
        var email = new Notification("n-1", "club-a", "account-a", "N-04", "EMAIL", Notification.Status.QUEUED, null, null, null, "laura@example.test", "ca", NOW, null);
        assertThat(email.dedupKey()).isEqualTo("n-1");
        assertThat(email.category()).isEqualTo(NotificationCategory.OPERATIONAL);
        assertThat(email.audience()).isEqualTo(NotificationAudience.MEMBER);
        assertThat(email.icon()).isEqualTo(TemplateIcon.check); assertThat(email.color()).isEqualTo(TemplateColor.OK);
        assertThat(email.recipient()).isEqualTo(new Notification.Recipient("account-a", null, null, "laura@example.test", null));
        assertThat(email.deliveries()).singleElement().isEqualTo(new Notification.Delivery(NotificationChannel.EMAIL, "laura@example.test", DeliveryStatus.QUEUED,
                0, null, null, null, null, null, null));
        assertThat(email.delivery()).isSameAs(email.deliveries().getFirst());
        assertThat(email.accountId()).isEqualTo("account-a"); assertThat(email.channel()).isEqualTo("EMAIL"); assertThat(email.status()).isEqualTo(Notification.Status.QUEUED);
        var app = new Notification("n-2", "club-a", "account-a", "N-15", "APP", Notification.Status.SENT, null, NOW, null, null, "ca", NOW, null);
        assertThat(app.delivery().target()).isEqualTo("account-a"); assertThat(app.delivery().sentAt()).isEqualTo(NOW);
        var sms = new Notification("n-3", "club-a", "account-a", "N-15", "SMS", Notification.Status.SKIPPED_MODULE_OFF, null, null, null, null, "ca", NOW, null);
        assertThat(sms.delivery().target()).as("the phones stay in recipientPhones").isNull();
        assertThat(sms.delivery().status()).isEqualTo(DeliveryStatus.SKIPPED_MODULE_OFF);
        var failed = new Notification("n-4", null, "account-a", "N-26", "EMAIL", Notification.Status.FAILED, null, null, "x", "a@example.test", "en", NOW, null);
        assertThat(failed.delivery().failedAt()).isEqualTo(NOW); assertThat(failed.clubId()).isNull(); assertThat(failed.category()).isEqualTo(NotificationCategory.SYSTEM);
        var delivered = new Notification("n-5", null, "account-a", "N-25", "EMAIL", Notification.Status.DELIVERED, "ref", NOW, null, "a@example.test", "en", NOW, null);
        assertThat(delivered.delivery().deliveredAt()).isEqualTo(NOW);
        assertThat(failed.toString()).doesNotContain("a@example.test").contains("n-4", "N-26", "FAILED");
        // The compatibility status and the S11 delivery status have the same values (catalog rule 7 + the three S11 §13 proposals).
        assertThat(Arrays.stream(Notification.Status.values()).map(Enum::name)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(DeliveryStatus.values()).map(Enum::name).toList());
    }

    @Test void WP_11_A_aHelperRowStatesItsAudienceOnlyWhenItKnowsIt() {
        assertThat(Notification.legacyAudience("N-01", "account-a", "admin")).isEqualTo(NotificationAudience.ADMINS);
        assertThat(Notification.legacyAudience("N-01", null, "upfront")).isEqualTo(NotificationAudience.APPLICANT);
        assertThat(Notification.legacyAudience("N-01", "account-a", "upfront")).as("the applicant copy on the member's feed").isNull();
        assertThat(Notification.legacyAudience("N-08a", "account-a", null)).as("a member or the class's instructor").isNull();
        assertThat(Notification.legacyAudience("N-21", "account-a", null)).isEqualTo(NotificationAudience.INSTRUCTORS);
        assertThat(Notification.legacyAudience("N-99", "account-a", null)).isNull();
        var unknown = new Notification("n-6", "club-a", "account-a", "N-99", "APP", Notification.Status.SENT, null, NOW, null, null, "ca", NOW, null);
        assertThat(unknown.category()).isNull(); assertThat(unknown.icon()).isEqualTo(TemplateIcon.bell); assertThat(unknown.color()).isEqualTo(TemplateColor.NEUTRAL);
    }

    @Test void WP_11_A_legacyRowsGainTheS11FieldsAndKeepTheirFlatOnes() {
        var email = new Document("_id", "n-1").append("clubId", "club-a").append("accountId", "account-a").append("code", "N-04").append("channel", "EMAIL")
                .append("status", "SENT").append("providerMessageId", "ref-1").append("sentAt", Date.from(NOW)).append("recipientEmail", "laura@example.test")
                .append("createdAt", Date.from(NOW));
        var fields = LegacyNotificationRows.upgrade(email);
        assertThat(fields.getString("dedupKey")).isEqualTo("n-1");
        assertThat(fields).containsEntry("category", "OPERATIONAL").containsEntry("audience", "MEMBER").containsEntry("icon", "check").containsEntry("color", "OK");
        assertThat(fields.get("recipient")).isEqualTo(new Document("accountId", "account-a").append("email", "laura@example.test"));
        assertThat(fields.getList("deliveries", Document.class)).singleElement().satisfies(delivery -> {
            assertThat(delivery).containsEntry("channel", "EMAIL").containsEntry("target", "laura@example.test").containsEntry("status", "SENT")
                    .containsEntry("attempts", 1).containsEntry("providerRef", "ref-1").containsEntry("sentAt", Date.from(NOW));
        });
        assertThat(fields).doesNotContainKeys("accountId", "channel", "status", "action", "smsBody");
        // The E4–E6 SMS intent: the bare `action` string becomes {type, params}; the text is also the smsBody.
        var sms = new Document("_id", "n-2").append("clubId", "club-a").append("accountId", "account-a").append("code", "N-15").append("channel", "SMS")
                .append("status", "QUEUED").append("body", "Club: plaça lliure").append("action", "CLAIM_SEAT").append("entityId", "entry-a").append("createdAt", Date.from(NOW));
        var smsFields = LegacyNotificationRows.upgrade(sms);
        assertThat(smsFields.get("action")).isEqualTo(new Document("type", "CLAIM_SEAT").append("params", new Document("entityId", "entry-a")));
        assertThat(smsFields).containsEntry("smsBody", "Club: plaça lliure");
        // E7-T02 round 2 (ruling E69): the queued intent written before the engine is closed, never sent — its delivery and flat status.
        assertThat(smsFields.getList("deliveries", Document.class).getFirst()).containsEntry("target", null).containsEntry("attempts", 0)
                .containsEntry("status", "SKIPPED_STALE").containsEntry("lastError", LegacyNotificationRows.BEFORE_ENGINE);
        assertThat(smsFields).containsEntry("status", "SKIPPED_STALE").containsEntry("error", LegacyNotificationRows.BEFORE_ENGINE);
        var push = LegacyNotificationRows.upgrade(new Document(sms).append("channel", "PUSH"));
        assertThat(push.getList("deliveries", Document.class).getFirst()).containsEntry("channel", "PUSH").containsEntry("status", "SKIPPED_STALE");
        // An SMS row that is no longer queued (the module was off) keeps its status.
        var skipped = LegacyNotificationRows.upgrade(new Document(sms).append("status", "SKIPPED_MODULE_OFF"));
        assertThat(skipped.getList("deliveries", Document.class).getFirst()).containsEntry("status", "SKIPPED_MODULE_OFF").containsEntry("lastError", null);
        assertThat(skipped).doesNotContainKeys("status", "error");
        var odd = LegacyNotificationRows.upgrade(new Document(sms).append("action", "SOMETHING_ELSE").append("code", "N-99"));
        assertThat(odd).containsEntry("action", null).containsEntry("legacyAction", "SOMETHING_ELSE").doesNotContainKeys("category", "audience");
        // A SYSTEM row without a channel field is an e-mail (E1).
        var system = LegacyNotificationRows.upgrade(new Document("_id", "n-3").append("code", "N-26").append("status", "FAILED").append("error", "x")
                .append("recipientEmail", "a@example.test").append("createdAt", Date.from(NOW)));
        assertThat(system.getList("deliveries", Document.class).getFirst()).containsEntry("channel", "EMAIL").containsEntry("failedAt", Date.from(NOW))
                .containsEntry("lastError", "x");
    }

    @Test void WP_11_A_templatesKeepTheS11MatrixShapeAndKindRules() {
        var title = new LocalizedText(Map.of("ca", "Reserva confirmada"), "ca");
        var matrix = Map.of(NotificationAudience.MEMBER, Map.of(NotificationChannel.APP, true, NotificationChannel.EMAIL, false));
        var template = new MessageTemplate("t-1", "club-a", "N-04", TemplateKind.CATALOG, NotificationCategory.OPERATIONAL, title, title, null, TemplateIcon.check,
                TemplateColor.OK, matrix, true, false, false, TemplateStatus.ACTIVE, 0L, NOW, "seed", NOW, "seed");
        assertThat(template.matrix()).isEqualTo(matrix);
        assertThatThrownBy(() -> new MessageTemplate("t-2", "club-a", null, TemplateKind.CATALOG, NotificationCategory.OPERATIONAL, title, title, null, TemplateIcon.check,
                TemplateColor.OK, matrix, true, false, false, TemplateStatus.ACTIVE, 0L, NOW, null, NOW, null)).hasMessageContaining("CATALOG");
        assertThatThrownBy(() -> new MessageTemplate("t-3", "club-a", "N-24", TemplateKind.CUSTOM, NotificationCategory.CLUB_NEWS, title, title, null, TemplateIcon.check,
                TemplateColor.OK, matrix, true, false, false, TemplateStatus.ACTIVE, 0L, NOW, null, NOW, null)).hasMessageContaining("CUSTOM");
        assertThatThrownBy(() -> new MessageTemplate("t-4", "club-a", "N-25", TemplateKind.CATALOG, NotificationCategory.SYSTEM, title, title, null, TemplateIcon.check,
                TemplateColor.OK, matrix, true, false, false, TemplateStatus.ACTIVE, 0L, NOW, null, NOW, null)).hasMessageContaining("SYSTEM");
        assertThatThrownBy(() -> new MessageTemplate("t-5", "club-a", null, TemplateKind.CUSTOM, NotificationCategory.CLUB_NEWS, title, title, null, TemplateIcon.check,
                TemplateColor.OK, Map.of(NotificationAudience.APPLICANT, Map.of(NotificationChannel.EMAIL, true)), true, false, false, TemplateStatus.ACTIVE, 0L, NOW, null, NOW, null))
                .hasMessageContaining("APPLICANT");
        assertThatThrownBy(() -> new MessageTemplate("t-6", "club-a", null, TemplateKind.CUSTOM, NotificationCategory.CLUB_NEWS, title, title, null, TemplateIcon.check,
                TemplateColor.OK, Map.of(NotificationAudience.MEMBER, Map.of(NotificationChannel.PUSH, true)), true, false, false, TemplateStatus.ACTIVE, 0L, NOW, null, NOW, null))
                .hasMessageContaining("PUSH");
        assertThat(new MessageTemplate("t-7", "club-a", null, TemplateKind.CUSTOM, NotificationCategory.CLUB_NEWS, title, title, null, TemplateIcon.bell,
                TemplateColor.NEUTRAL, null, true, false, false, TemplateStatus.ARCHIVED, 0L, NOW, null, NOW, null).matrix()).isEmpty();
    }

    @Test void WP_11_A_aPushSubscriptionIsUniqueByTheEndpointHashAndNeverPrintsItsSecrets() {
        String endpoint = "https://push.example.test/send/abc123";
        String p256dh = com.agilityhub.core.clubs.messaging.support.PushKeyFixtures.p256dh(), auth = com.agilityhub.core.clubs.messaging.support.PushKeyFixtures.auth();
        var subscription = new PushSubscription("s-1", "club-a", "account-a", endpoint, null, new PushSubscription.Keys(p256dh, auth), "iPhone · Safari",
                "UA", PushSubscription.Status.ACTIVE, 0, null, null, 0L, NOW, "account-a", NOW, "account-a");
        assertThat(subscription.endpointHash()).isEqualTo(PushSubscription.hash(endpoint)).hasSize(64).matches("[0-9a-f]+");
        assertThat(subscription.toString()).doesNotContain(endpoint, p256dh, auth);
        assertThat(subscription.keys().toString()).doesNotContain(p256dh, auth);
        assertThatThrownBy(() -> new PushSubscription("s-2", "club-a", "account-a", endpoint, "0".repeat(64), subscription.keys(), null, null,
                PushSubscription.Status.ACTIVE, 0, null, null, 0L, NOW, null, NOW, null)).hasMessageContaining("SHA-256");
        assertThat(PushSubscription.hash(endpoint + "x")).isNotEqualTo(subscription.endpointHash());
    }
}
