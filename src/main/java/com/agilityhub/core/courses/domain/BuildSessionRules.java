package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseTypes.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.IntUnaryOperator;

/**
 * S16 R-16-11, the build session's rules that need no storage: which statuses are open (§5: `OPEN` = `NOT_STARTED` or
 * `IN_PROGRESS`), when an open session has outlived `courses.buildSessionMaxHours`, the join code another phone types, and the
 * setup `kind` a finished session registers (the course's discipline).
 */
public final class BuildSessionRules {
    /** Six characters without the ones a reader confuses (0/O, 1/I/L): about 10⁹ codes, unique among a club's open sessions. */
    public static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;
    private BuildSessionRules() { }

    /** §5: a session can still change while it is `NOT_STARTED` or `IN_PROGRESS`. */
    public static boolean open(BuildStatus status) { return status == BuildStatus.NOT_STARTED || status == BuildStatus.IN_PROGRESS; }

    /**
     * R-16-11 «Una sessió oberta > 12 h es tanca sola»: an open session started (else created) {@code maxHours} or more before
     * {@code now} is over, even before P9 records it `ABANDONED`.
     */
    public static boolean outlived(BuildStatus status, Instant startedAt, Instant createdAt, int maxHours, Instant now) {
        Instant from = startedAt != null ? startedAt : createdAt;
        return open(status) && from != null && !from.plus(Duration.ofHours(maxHours)).isAfter(now);
    }

    /** A join code drawn with {@code random} (an index below its bound, e.g. a `SecureRandom::nextInt`). */
    public static String joinCode(IntUnaryOperator random) {
        var code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) { code.append(CODE_ALPHABET.charAt(random.applyAsInt(CODE_ALPHABET.length()))); }
        return code.toString();
    }

    /** What a person types: case and surrounding blanks do not matter. */
    public static String normalizeCode(String typed) { return typed == null ? "" : typed.strip().toUpperCase(Locale.ROOT); }

    /**
     * R-16-11 «`kind` de la disciplina del recorregut»: an agility course is set up as `AGILITY`, a jumping one as `JUMPING`, and
     * any other discipline as `FUN` (S16 §3 has no setup kind for «other»).
     */
    public static SetupKind kind(Discipline discipline) {
        return switch (discipline) {
            case AGILITY -> SetupKind.AGILITY;
            case JUMPING -> SetupKind.JUMPING;
            case OTHER -> SetupKind.FUN;
        };
    }
}
