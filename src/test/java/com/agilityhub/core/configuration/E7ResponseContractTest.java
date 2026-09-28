package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.api.MessagingContracts;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.NotificationEvent;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.databind.*;
import java.lang.reflect.RecordComponent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T01: the S11 §6 forms round-trip every documented field, nulls included (the api sends a nullable field as `null`); the
 * two §6 JSON extracts are the fixtures and round-trip; the §7 events carry exactly their catalog payloads.
 */
class E7ResponseContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    static Class<?> type(String fixtureKey) throws ClassNotFoundException { return Class.forName(MessagingContracts.class.getName() + "$" + fixtureKey.split("#")[0]); }
    private JsonNode fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/" + name + ".json")) { return mapper.readTree(input); }
    }
    private void roundTrip(String label, JsonNode json, Class<?> type) throws Exception {
        Object value = mapper.treeToValue(json, type);
        JSONAssert.assertEquals(label, json.toString(), mapper.writeValueAsString(value), true);
    }

    @Test void T_11_16_T_11_19_T_11_26_formsKeepEveryDocumentedFieldDuringRoundTrip() throws Exception {
        int forms = 0;
        var fields = fixture("e7-messaging-responses").fields();
        while (fields.hasNext()) { var entry = fields.next(); roundTrip(entry.getKey(), entry.getValue(), type(entry.getKey())); forms++; }
        assertThat(forms).isEqualTo(22);
    }

    /** The two JSON extracts of S11 §6 are the fixtures, byte for byte in JSON terms, and round-trip through the published forms. */
    @Test void T_11_19_T_11_20_theTwoS11JsonExtractsAreTheFixturesAndRoundTrip() throws Exception {
        String s11 = Files.readString(Path.of("docs/specs/S11-comunicacions.md"));
        JSONAssert.assertEquals(extract(s11, "`GET /me/notifications` (200, extracte):"), fixture("e7-me-notifications").toString(), true);
        JSONAssert.assertEquals(extract(s11, "`GET /me/notification-preferences` (200):"), fixture("e7-notification-preferences").toString(), true);
        roundTrip("MeNotifications", fixture("e7-me-notifications"), MessagingContracts.MeNotifications.class);
        roundTrip("NotificationPreferences", fixture("e7-notification-preferences"), MessagingContracts.NotificationPreferences.class);
        assertThat(fixture("e7-me-notifications").path("items")).allSatisfy(item -> assertThat(item.has("readAt") && item.path("readAt").isNull()).isTrue());
    }
    private static String extract(String spec, String heading) {
        int start = spec.indexOf("```json", spec.indexOf(heading)) + "```json".length();
        return spec.substring(start, spec.indexOf("```", start));
    }

    /** T-11-20 «PUT parcial no toca la resta»: an absent key, `null` («Mai») and a value are three different requests. */
    @Test void T_11_20_anAbsentReminderKeyIsNotTheNullOfMai() throws Exception {
        var absent = mapper.readValue("{\"pushClubNews\": false}", MessagingContracts.NotificationPreferencesRequest.class);
        var never = mapper.readValue("{\"reminderMinutesBefore\": null}", MessagingContracts.NotificationPreferencesRequest.class);
        var twoHours = mapper.readValue("{\"reminderMinutesBefore\": 120}", MessagingContracts.NotificationPreferencesRequest.class);
        assertThat(absent.reminderMinutesBefore()).isNull(); assertThat(absent.emailByCategory()).isNull();
        assertThat(never.reminderMinutesBefore()).isEqualTo(new MessagingContracts.ReminderChoice(null));
        assertThat(twoHours.reminderMinutesBefore()).isEqualTo(new MessagingContracts.ReminderChoice(120));
        assertThatThrownBy(() -> mapper.readValue("{\"reminderMinutesBefore\": \"soon\"}", MessagingContracts.NotificationPreferencesRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
        var partial = mapper.readValue("{\"emailByCategory\": {\"CLUB_NEWS\": false}}", MessagingContracts.NotificationPreferencesRequest.class);
        assertThat(partial.emailByCategory().clubNews()).isFalse(); assertThat(partial.emailByCategory().operational()).isNull();
    }

    /** R-11-10/R-11-11: the member's feed never carries delivery targets, provider references or the template. */
    @Test void T_11_19_theFeedFormExposesNoDeliveryDataAndTheLogFormDoes() {
        assertThat(Arrays.stream(MessagingContracts.MeNotification.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("deliveries", "recipient", "templateId", "providerRef", "target", "smsBody");
        assertThat(Arrays.stream(MessagingContracts.NotificationDetail.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("deliveries", "subject", "templateId", "templateVersion", "eventType", "locale");
        assertThat(Arrays.stream(MessagingContracts.DeliveryView.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("channel", "target", "status", "attempts", "nextAttemptAt", "providerRef", "lastError", "sentAt", "deliveredAt", "failedAt");
    }

    @Test void WP_11_A_eventsHaveExactlyTheS11PayloadFieldsAndEnvelope() throws Exception {
        var fixtures = fixture("e7-events");
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("MessageTemplateChanged", "id,diff"); fields.put("AnnouncementSent", "templateId,batchId,recipientCount,filters");
        fields.put("PushSubscribed", "accountId,endpoint"); fields.put("PushUnsubscribed", "accountId,endpoint");
        fields.put("EmailBounced", "memberId,email,type"); fields.put("SmsCapReached", "month,cap");
        fields.put("NotificationPreferencesChanged", "memberId,diff,byAccountId"); fields.put("EmailUnsubscribed", "memberId");
        assertThat(fixtures.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(fields.keySet());
        assertThat(Arrays.stream(MessagingEvent.Kind.values()).map(Enum::name)).containsExactlyInAnyOrderElementsOf(fields.keySet());
        Map<String, String> aggregates = Map.of("MessageTemplateChanged", "MessageTemplate", "AnnouncementSent", "Announcement", "PushSubscribed", "PushSubscription",
                "PushUnsubscribed", "PushSubscription", "EmailBounced", "Member", "SmsCapReached", "Club", "NotificationPreferencesChanged", "Member", "EmailUnsubscribed", "Member");
        for (var entry : fields.entrySet()) {
            String name = entry.getKey();
            assertThat(catalog).contains("`" + name);
            assertThat(fixtures.path(name).fieldNames()).toIterable().as(name).containsExactlyInAnyOrder(entry.getValue().split(","));
            @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(fixtures.path(name), Map.class);
            var event = new MessagingEvent(MessagingEvent.Kind.valueOf(name), "club-a", "aggregate-a", Instant.parse("2026-09-28T07:00:00Z"), payload,
                    "account-a", null, DomainEvent.Origin.BACKOFFICE);
            assertThat(event.type()).isEqualTo(name); assertThat(event.aggregateType()).isEqualTo(aggregates.get(name));
            assertThat(event.payload()).isEqualTo(payload);
            assertThatThrownBy(() -> event.payload().put("extra", true)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(mapper.readValue(mapper.writeValueAsString(event), MessagingEvent.class)).isEqualTo(event);
        }
        // The catalog rows: the «Catàlegs» row's `id, diff`, AnnouncementSent and Push* in «Comunicacions», the four proposals in Annex A.
        assertThat(row(catalog, "| `LevelChanged` /")).contains("`MessageTemplateChanged`", "| id, diff |");
        assertThat(row(catalog, "| `AnnouncementSent` |")).contains("templateId, recipientCount, filters");
        assertThat(row(catalog, "| `PushSubscribed` / `PushUnsubscribed` |")).contains("accountId, endpoint");
        // Annex A's S11 row lists the payloads of S11 §7 since E66 (27-09): the fixtures' fields, in the catalog's order.
        var proposals = row(catalog, "| `EmailBounced{");
        for (String name : List.of("EmailBounced", "SmsCapReached")) {
            var payload = java.util.regex.Pattern.compile("`" + name + "\\{([^}]*)}`").matcher(proposals);
            assertThat(payload.find()).as(name + " in the catalog row").isTrue();
            assertThat(payload.group(1).split(", ")).as(name).containsExactly(fields.get(name).split(","));
        }
        assertThat(proposals).contains("`NotificationPreferencesChanged`", "`EmailUnsubscribed`");
        // The endpoint is its SHA-256, never the URL (the fixture is a hash, as PushSubscription.endpointHash).
        assertThat(fixtures.at("/PushSubscribed/endpoint").asText()).matches("[0-9a-f]{64}");
        // NotificationQueued/Sent/Failed{notificationId, channel}: the channel is the delivery's, not always EMAIL.
        var queued = new NotificationEvent(NotificationEvent.Kind.NotificationQueued, "club-a", "notification-a", Instant.parse("2026-09-28T07:00:00Z"), NotificationChannel.SMS);
        assertThat(queued.payload()).containsExactlyInAnyOrderEntriesOf(Map.of("notificationId", "notification-a", "channel", "SMS"));
        assertThat(queued.origin()).isEqualTo(DomainEvent.Origin.SYSTEM);
        assertThat(new NotificationEvent(NotificationEvent.Kind.NotificationFailed, null, "n", Instant.EPOCH, DomainEvent.Origin.WEBHOOK).payload()).containsEntry("channel", "EMAIL");
        assertThat(row(catalog, "| `NotificationQueued` /")).contains("notificationId, channel");
        assertThatThrownBy(() -> new NotificationEvent(NotificationEvent.Kind.NotificationSent, "club-a", "n", Instant.EPOCH, DomainEvent.Origin.SYSTEM, null))
                .isInstanceOf(NullPointerException.class);
    }
    private static String row(String catalog, String prefix) {
        return catalog.lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError(prefix));
    }
}
