package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.shared.application.HostedDemoPolicy;
import java.util.Objects;

/** Refused demo recipients never reach the real sender; no message or address is exposed in the error. */
public final class AllowListEmailSender implements EmailSender {
    public static final String NOT_ALLOWED = "Email recipient not allowed in hosted demo";
    private final EmailSender delegate;
    private final HostedDemoPolicy policy;
    public AllowListEmailSender(EmailSender delegate, HostedDemoPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate); this.policy = Objects.requireNonNull(policy);
    }
    @Override public SendResult send(EmailMessage message) {
        return policy.permitsEmail(message.to()) ? delegate.send(message) : SendResult.failed(NOT_ALLOWED);
    }
}
