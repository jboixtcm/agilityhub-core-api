package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.application.contract.ListContract;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * E5-T24 (CONVENCIONS_API §4, amended 26-09; review E5-T22 #2 and nit #7): the list contracts are checked when the application
 * starts, so a misdeclared one never reaches the OpenAPI document. An operation publishes the `fields` parameter exactly when it
 * is `paged` and accepts `fields`, and then its `x-fields` must list the keys; `acceptsFields = false` with `fields` is refused.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ListContractValidation {
    @Bean SmartInitializingSingleton listContracts(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        return () -> check(mappings.getHandlerMethods().values());
    }

    static void check(Collection<HandlerMethod> handlers) {
        var problems = new ArrayList<String>();
        for (var handler : handlers) {
            var list = handler.getMethodAnnotation(ListContract.class);
            if (list == null) { continue; }
            String name = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
            boolean parameter = list.paged() && list.acceptsFields();
            if (!list.acceptsFields() && list.fields().length > 0) { problems.add(name + ": acceptsFields = false with fields"); }
            else if (parameter && list.fields().length == 0) { problems.add(name + ": publishes fields without x-fields"); }
            else if (!parameter && list.fields().length > 0) { problems.add(name + ": publishes x-fields without the fields parameter"); }
        }
        if (!problems.isEmpty()) { throw new IllegalStateException("Invalid @ListContract (CONVENCIONS_API §4): " + problems); }
    }
}
