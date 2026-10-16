package com.agilityhub.core.courses.application;

import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import org.springframework.test.util.AopTestUtils;

/**
 * E9-T06: lets an integration test choose the join-code draws of the real {@link BuildSessionService} and, round 2, run another
 * write at a named point of one (`join` between its read and its write, `finish` after its checks).
 */
public final class BuildSessionDraws {
    private BuildSessionDraws() { }
    public static void set(BuildSessionService service, IntUnaryOperator draws) {
        AopTestUtils.<BuildSessionService>getUltimateTargetObject(service).draws(draws);
    }
    public static void pauses(BuildSessionService service, Consumer<String> pauses) {
        AopTestUtils.<BuildSessionService>getUltimateTargetObject(service).pauses(pauses);
    }
}
