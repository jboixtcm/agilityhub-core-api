package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.api.TestClockController;
import com.agilityhub.core.shared.application.MutableClock;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E5-T27 step 6 (A7-05; S15 WP-15-E): the movable clock and `POST /api/v1/test/clock` follow the profile expression of the other
 * test-only beans, `(local | test) & !staging & !prod`. A context started with `local,staging` has neither the route nor the
 * movable clock: its clock is the system clock. The route's own `@PreAuthorize` denies it whenever that expression is false.
 */
class TestClockProfileGuardTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withUserConfiguration(ClockConfiguration.class, TestClockController.class);

    @Test void A7_05_stagingOrProdNeverHaveTheTestClockRouteNorTheMovableClock() {
        for (String profiles : List.of("local,staging", "test,staging", "local,prod", "test,prod", "staging", "prod")) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).as(profiles).hasNotFailed().doesNotHaveBean(TestClockController.class);
                var clock = context.getBean(Clock.class);
                assertThat(clock).as(profiles).isNotInstanceOf(MutableClock.class);
                assertThat(clock.getClass().getSimpleName()).as(profiles).isEqualTo("SystemClock");
                assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
                MockMvcBuilders.webAppContextSetup(context).build()
                        .perform(post("/api/v1/test/clock").contentType("application/json").content("{\"advanceSeconds\":60}"))
                        .andExpect(status().isNotFound());
                assertThat(guard(context)).as(profiles).isFalse();
            });
        }
    }

    @Test void A7_05_localOrTestAloneKeepTheMovableClockAndTheRoute() {
        for (String profiles : List.of("local", "test")) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).as(profiles).hasSingleBean(TestClockController.class);
                assertThat(context.getBean(Clock.class)).as(profiles).isInstanceOf(MutableClock.class);
                MockMvcBuilders.webAppContextSetup(context).build()
                        .perform(post("/api/v1/test/clock").contentType("application/json").content("{\"instant\":\"2030-01-01T00:00:00Z\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.now", org.hamcrest.Matchers.startsWith("2030-01-01T00:00:0")));
                assertThat(guard(context)).as(profiles).isTrue();
            });
        }
    }

    /** The route's `@PreAuthorize`, evaluated like method security does: `@environment` resolves to the context's environment. */
    private static boolean guard(org.springframework.context.ApplicationContext context) throws NoSuchMethodException {
        var annotation = TestClockController.class.getMethod("move", TestClockController.ClockRequest.class).getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("the route carries its own profile guard").isNotNull();
        var evaluation = new StandardEvaluationContext();
        evaluation.setBeanResolver(new BeanFactoryResolver(context));
        return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(annotation.value()).getValue(evaluation, Boolean.class));
    }
}
