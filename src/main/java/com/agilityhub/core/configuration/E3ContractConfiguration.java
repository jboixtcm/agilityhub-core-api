package com.agilityhub.core.configuration;

import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Public S04 operations which also accept a member's bearer token. */
@Configuration(proxyBeanMethods = false)
public class E3ContractConfiguration {
    @Bean OpenApiCustomizer optionalSignupAuthentication() {
        return api -> {
            var optionalBearer = List.of(new SecurityRequirement(), new SecurityRequirement().addList("bearer"));
            api.getPaths().get("/api/v1/signup").getGet().setSecurity(optionalBearer);
            api.getPaths().get("/api/v1/signup/upload-urls").getPost().setSecurity(optionalBearer);
            api.getPaths().get("/api/v1/checkout-sessions").getPost().setSecurity(optionalBearer);
        };
    }
}
