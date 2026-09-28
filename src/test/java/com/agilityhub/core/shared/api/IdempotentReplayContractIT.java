package com.agilityhub.core.shared.api;

import com.agilityhub.core.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONVENCIONS_API §7 (ruling E62): a repeated `Idempotency-Key` is answered by {@link IdempotentReplayAspect} in place
 * of the handler, after the handler's own authorization. A handler method outside the aspect would run a second time on
 * a replay, so every handler method of the api must be advised by it, inside method security.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class IdempotentReplayContractIT extends AbstractIntegrationTest {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired ApplicationContext context;

    @Test void E62_everyHandlerMethodOfTheApiAnswersAReplayOnlyInsideItsOwnAuthorization() {
        var missing = new ArrayList<String>(); int checked = 0;
        for (HandlerMethod handler : mappings.getHandlerMethods().values()) {
            if (!handler.getBeanType().getName().startsWith("com.agilityhub.core.")) { continue; }
            checked++;
            Object bean = handler.getBean() instanceof String name ? context.getBean(name) : handler.getBean();
            if (!(bean instanceof Advised advised)) { missing.add(handler.toString()); continue; }
            int replay = -1, preAuthorize = -1; var advisors = advised.getAdvisors();
            for (int i = 0; i < advisors.length; i++) {
                if (advisors[i] instanceof PointcutAdvisor pointcut && pointcut.getAdvice() instanceof AbstractAspectJAdvice advice
                        && "idempotentReplayAspect".equals(advice.getAspectName())
                        && pointcut.getPointcut().getMethodMatcher().matches(handler.getMethod(), AopUtils.getTargetClass(bean))) { replay = i; }
                if (advisors[i].getAdvice().getClass().getSimpleName().equals("AuthorizationManagerBeforeMethodInterceptor")) { preAuthorize = i; }
            }
            if (replay < 0 || replay < preAuthorize) { missing.add(handler + " (replay " + replay + ", method security " + preAuthorize + ")"); }
        }
        System.out.println("E62 handler methods behind the replay aspect: " + checked + ", missing " + missing.size());
        assertThat(checked).isGreaterThan(100);
        assertThat(missing).isEmpty();
    }

    @Autowired IdempotencyFilter filter;

    /**
     * E5-T27 step 4 (INC-23, ruling E46; CONVENCIONS_API §7): every operation whose parameters in the committed OpenAPI snapshot
     * include a required `Idempotency-Key` header is filtered with that header, whatever its method; the filter's non-POST routes
     * are exactly the snapshot's; a GET is never filtered. The list comes from the snapshot, not from this test.
     */
    @Test void E46_everyOperationThatRequiresAnIdempotencyKeyInTheSnapshotIsKeyedByTheFilter() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var api = mapper.readTree(java.nio.file.Path.of("docs/openapi/openapi.json").toFile());
        var required = new java.util.TreeSet<String>(); var declaredNonPost = new java.util.TreeSet<String>();
        api.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
            for (var parameter : operation.getValue().path("parameters")) {
                if (parameter.has("$ref")) { parameter = api.at(parameter.path("$ref").asText().substring(1)); }
                if (!"header".equals(parameter.path("in").asText()) || !"Idempotency-Key".equalsIgnoreCase(parameter.path("name").asText())) { continue; }
                String id = operation.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey();
                if (parameter.path("required").asBoolean()) { required.add(id); }
                if (!id.startsWith("POST ")) { declaredNonPost.add(id); }
            }
        }));
        System.out.println("E46 operations with a required Idempotency-Key in the snapshot: " + required);
        assertThat(required).contains("PUT /api/v1/class-sessions/{id}/attendance", "PUT /api/v1/dogs/{id}/observations",
                "DELETE /api/v1/tasks/{id}", "DELETE /api/v1/attachments/{id}", "POST /api/v1/bookings");
        for (String operation : required) {
            String method = operation.substring(0, operation.indexOf(' ')), path = operation.substring(operation.indexOf(' ') + 1).replaceAll("\\{[^}]+}", "example-id");
            var request = new org.springframework.mock.web.MockHttpServletRequest(method, path);
            request.addHeader("Idempotency-Key", java.util.UUID.randomUUID().toString());
            assertThat(filter.shouldNotFilter(request)).as(operation).isFalse();
        }
        assertThat(filter.keyed().operations().stream().filter(operation -> !operation.startsWith("POST ")).toList())
                .as("the filter's keyed routes other than POST").containsExactlyInAnyOrderElementsOf(declaredNonPost);
        for (String path : List.of("/api/v1/class-sessions/example-id/attendance", "/api/v1/tasks/example-id", "/api/v1/health")) {
            var read = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
            read.addHeader("Idempotency-Key", java.util.UUID.randomUUID().toString());
            assertThat(filter.shouldNotFilter(read)).as("GET " + path).isTrue();
        }
        var undeclared = new org.springframework.mock.web.MockHttpServletRequest("PUT", "/api/v1/me/profile");
        undeclared.addHeader("Idempotency-Key", java.util.UUID.randomUUID().toString());
        assertThat(filter.shouldNotFilter(undeclared)).as("a PUT that does not declare the header").isTrue();
    }
}
