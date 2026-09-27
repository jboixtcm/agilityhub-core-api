package com.agilityhub.core.clubs.messaging.application.integrations;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Test double (profile `test`): an in-memory outbox; {@link #failNext} scripts the next answers. */
public final class FakeSmsSender implements SmsSender {
    private final ConcurrentLinkedDeque<SmsMessage> sent = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<SendResult> scripted = new ConcurrentLinkedDeque<>();

    @Override public SendResult send(SmsMessage message) {
        var next = scripted.poll();
        if (next != null && !next.ok()) { return next; }
        sent.add(message);
        return SendResult.accepted("fake-sms-" + UUID.randomUUID());
    }
    /** The next `send` answers this result instead of accepting (one per call). */
    public void failNext(SendResult result) { scripted.add(result); }
    public List<SmsMessage> messages() { return new ArrayList<>(sent); }
    public SmsMessage lastTo(String phone) {
        var list = messages();
        for (int i = list.size() - 1; i >= 0; i--) { if (list.get(i).to().equals(phone)) { return list.get(i); } }
        throw new NoSuchElementException("No SMS to that number");
    }
    public void clear() { sent.clear(); scripted.clear(); }
}
