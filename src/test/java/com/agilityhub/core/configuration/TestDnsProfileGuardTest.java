package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.api.TestDnsController;
import com.agilityhub.core.platform.application.FakeDomainDns;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
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
 * E10-T03: `POST /api/v1/test/dns` and the fake resolver follow the test clock's profile expression, `(local | test) & !staging &
 * !prod` (A7-05). With `staging` or `prod` neither exists; under `local` alone a host without published addresses resolves to the
 * machine (the local default), under `test` alone to nothing; a published empty set is an explicit «no record».
 */
class TestDnsProfileGuardTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withPropertyValues("spring.http.converters.preferred-json-mapper=jackson2")
            .withUserConfiguration(FakeDomainDns.class, TestDnsController.class);
    static final String CNAME = "{\"type\":\"CNAME\",\"name\":\"App.Club.example.test.\",\"values\":[\"clubs.agilitydoghub.com\"]}";

    @Test void E10_T03_stagingOrProdNeverHaveTheTestDnsRouteNorTheFakeResolver() {
        for (String profiles : List.of("local,staging", "test,staging", "local,prod", "test,prod", "staging", "prod")) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).as(profiles).hasNotFailed().doesNotHaveBean(TestDnsController.class).doesNotHaveBean(FakeDomainDns.class);
                MockMvcBuilders.webAppContextSetup(context).build()
                        .perform(post("/api/v1/test/dns").contentType("application/json").content(CNAME)).andExpect(status().isNotFound());
                assertThat(guard(context)).as(profiles).isFalse();
            });
        }
    }

    @Test void E10_T03_localOrTestPublishRecordsAndOnlyLocalResolvesUnpublishedHostsToTheMachine() {
        for (String profiles : List.of("local", "test")) {
            runner.withPropertyValues("spring.profiles.active=" + profiles,
                    "core.platform.fake-dns.default-addresses=" + (profiles.equals("local") ? "127.0.0.1" : "")).run(context -> {
                var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                var dns = context.getBean(FakeDomainDns.class);
                mvc.perform(post("/api/v1/test/dns").contentType("application/json").content(CNAME))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("app.club.example.test"));
                assertThat(dns.cnames("APP.club.example.test")).containsExactly("clubs.agilitydoghub.com");
                // The TXT challenge's name is a DNS name, not a host: its underscore label is accepted.
                mvc.perform(post("/api/v1/test/dns").contentType("application/json")
                        .content("{\"type\":\"TXT\",\"name\":\"_agilityhub-verify.club.example.test\",\"values\":[\"agh-verify-x\"]}")).andExpect(status().isOk());
                assertThat(dns.txt("_agilityhub-verify.club.example.test")).containsExactly("agh-verify-x");
                assertThat(dns.addresses("unpublished.example.test")).as(profiles).isEqualTo(profiles.equals("local") ? List.of("127.0.0.1") : List.of());
                // A published empty set is a lost record: it overrides the default addresses too.
                mvc.perform(post("/api/v1/test/dns").contentType("application/json").content("{\"type\":\"A\",\"name\":\"lost.example.test\",\"values\":[]}"))
                        .andExpect(status().isOk());
                assertThat(dns.addresses("lost.example.test")).isEmpty();
                mvc.perform(post("/api/v1/test/dns").contentType("application/json").content("{\"type\":\"CNAME\",\"name\":\"app.club.example.test\",\"values\":[]}"))
                        .andExpect(status().isOk());
                assertThat(dns.cnames("app.club.example.test")).isEmpty();
                assertThat(guard(context)).as(profiles).isTrue();
            });
        }
    }

    private static boolean guard(org.springframework.context.ApplicationContext context) throws NoSuchMethodException {
        var annotation = TestDnsController.class.getMethod("publish", TestDnsController.DnsRequest.class).getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("the route carries its own profile guard").isNotNull();
        var evaluation = new StandardEvaluationContext();
        evaluation.setBeanResolver(new BeanFactoryResolver(context));
        return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(annotation.value()).getValue(evaluation, Boolean.class));
    }
}
