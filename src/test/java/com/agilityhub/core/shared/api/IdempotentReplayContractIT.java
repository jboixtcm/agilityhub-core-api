package com.agilityhub.core.shared.api;

import com.agilityhub.core.support.AbstractIntegrationTest;
import java.util.ArrayList;
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
}
