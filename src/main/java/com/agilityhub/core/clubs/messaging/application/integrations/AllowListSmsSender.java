package com.agilityhub.core.clubs.messaging.application.integrations;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The non-production guard of a real SMS provider (organizer 2026-09-24, E4-T05 review #1): the demo seeds and fixtures use
 * real-format Spanish mobile numbers, so outside `prod` only the numbers of `SMS_ALLOWED_NUMBERS` (comma-separated E.164;
 * empty = nobody) reach the provider; any other answers {@link SendResult#notAllowed()} (→ `SKIPPED_NOT_ALLOWED`).
 */
public final class AllowListSmsSender implements SmsSender {
    private final SmsSender delegate; private final Set<String> allowed;

    public AllowListSmsSender(SmsSender delegate, Set<String> allowed) {
        this.delegate = Objects.requireNonNull(delegate); this.allowed = Set.copyOf(allowed);
    }
    /** `SMS_ALLOWED_NUMBERS`: comma-separated, spaces ignored; blank = the empty list. */
    public static Set<String> parse(String numbers) {
        if (numbers == null || numbers.isBlank()) { return Set.of(); }
        return Arrays.stream(numbers.split(",")).map(number -> number.replace(" ", "").strip()).filter(number -> !number.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    @Override public SendResult send(SmsMessage message) {
        return allowed.contains(message.to()) ? delegate.send(message) : SendResult.notAllowed();
    }
    public SmsSender delegate() { return delegate; }
}
