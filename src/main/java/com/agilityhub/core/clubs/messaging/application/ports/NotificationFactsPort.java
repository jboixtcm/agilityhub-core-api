package com.agilityhub.core.clubs.messaging.application.ports;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The owning context of an event explains it to the notification engine (S11 §6): which catalog codes of the event apply
 * (S11 §7 «Particularitats»), who they are about and the raw values of their variables. One adapter per owning context,
 * in its `application` package (the engine never imports another context). An event type without any adapter gets the
 * engine's defaults over its payload; with adapters, the first non-empty answer wins, and all-empty means «no notice».
 * Codes, audiences, channels and statuses are the catalog's names.
 *
 * <p>Owners with a business step tied to the notice get the two hooks: {@link #stored} runs in the engine's transaction
 * right after the code's notifications were stored (S08 R-08-13: the N-15 offer mark), {@link #sent} after a provider
 * accepted a delivery, outside any transaction (S10 §7: the N-19 «avís ja enviat»).</p>
 */
public interface NotificationFactsPort {
    /** The catalog event types (`CATALEG_ESDEVENIMENTS` wire names) this owner explains. */
    Set<String> eventTypes();
    /** The facts of one catalog code (`N-08a` …) of the event, or empty when this event does not produce the code. */
    Optional<NotificationFacts> facts(NotificationTrigger trigger, String code);
    /** Inside the engine's transaction, after the code's notifications of the event were stored (reprocessing included). */
    default void stored(NotificationTrigger trigger, String code, List<StoredNotification> notifications) { }
    /** A delivery of a notification this owner explained was accepted by its provider (`SENT`); outside any transaction. */
    default void sent(StoredNotification notification, String channel) { }

    /** A stored notification as its owner sees it: who, about what, and its deliveries. */
    record StoredNotification(String notificationId, String code, String eventType, String audience, String accountId,
            String memberId, NotificationSubject subject, List<DeliveryView> deliveries, boolean created) {
        public StoredNotification { deliveries = deliveries == null ? List.of() : List.copyOf(deliveries); }
        public boolean has(String channel, String status) {
            return deliveries.stream().anyMatch(delivery -> delivery.channel().equals(channel) && delivery.status().equals(status));
        }
        /** Whether a channel of the notice is on its way or reached the member (`QUEUED`, `SENT`, `DELIVERED`). */
        public boolean reachable() { return deliveries.stream().anyMatch(delivery -> Set.of("QUEUED", "SENT", "DELIVERED").contains(delivery.status())); }
    }
    record DeliveryView(String channel, String status) { }
}
