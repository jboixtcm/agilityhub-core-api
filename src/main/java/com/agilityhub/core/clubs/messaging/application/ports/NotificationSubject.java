package com.agilityhub.core.clubs.messaging.application.ports;

/**
 * What a notification is about (S11 §3 `subject`): the parameters of its native action and the filters of the log. Every
 * field is optional.
 */
public record NotificationSubject(String dogId, String bookingId, String classSessionId, String waitlistEntryId, String trainingBookingId,
        String invoiceId, String activityId, String taskId, String memberId) {
    public static final NotificationSubject NONE = new NotificationSubject(null, null, null, null, null, null, null, null, null);

    /** Each non-null field of `other` over this one. */
    public NotificationSubject with(NotificationSubject other) {
        if (other == null) { return this; }
        return new NotificationSubject(or(other.dogId, dogId), or(other.bookingId, bookingId), or(other.classSessionId, classSessionId),
                or(other.waitlistEntryId, waitlistEntryId), or(other.trainingBookingId, trainingBookingId), or(other.invoiceId, invoiceId),
                or(other.activityId, activityId), or(other.taskId, taskId), or(other.memberId, memberId));
    }
    public static NotificationSubject dog(String dogId) { return NONE.withDog(dogId); }
    public static NotificationSubject classSession(String id) { return new NotificationSubject(null, null, id, null, null, null, null, null, null); }
    public static NotificationSubject booking(String id) { return new NotificationSubject(null, id, null, null, null, null, null, null, null); }
    public static NotificationSubject trainingBooking(String id) { return new NotificationSubject(null, null, null, null, id, null, null, null, null); }
    public static NotificationSubject waitlistEntry(String id) { return new NotificationSubject(null, null, null, id, null, null, null, null, null); }
    public static NotificationSubject activity(String id) { return new NotificationSubject(null, null, null, null, null, null, id, null, null); }
    public static NotificationSubject task(String id) { return new NotificationSubject(null, null, null, null, null, null, null, id, null); }
    public static NotificationSubject member(String id) { return new NotificationSubject(null, null, null, null, null, null, null, null, id); }
    public NotificationSubject withDog(String id) {
        return new NotificationSubject(id, bookingId, classSessionId, waitlistEntryId, trainingBookingId, invoiceId, activityId, taskId, memberId);
    }
    private static String or(String preferred, String fallback) { return preferred != null ? preferred : fallback; }
}
