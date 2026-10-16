package com.agilityhub.core.courses.application;

import java.util.function.IntUnaryOperator;
import org.springframework.test.util.AopTestUtils;

/** E9-T06: lets an integration test choose the join-code draws of the real {@link BuildSessionService}. */
public final class BuildSessionDraws {
    private BuildSessionDraws() { }
    public static void set(BuildSessionService service, IntUnaryOperator draws) {
        AopTestUtils.<BuildSessionService>getUltimateTargetObject(service).draws(draws);
    }
}
