package com.agilityhub.core.configuration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * M17 (R-04-20, R-04-17): behind Caddy, `getRemoteAddr()` is the proxy's address unless Tomcat trusts it
 * (`server.forward-headers-strategy: native` + `server.tomcat.remoteip.internal-proxies`, fed by `TRUSTED_PROXY_PATTERN`).
 * Without the pattern every applicant of a club would share one limiter bucket and one consent `ipHash`, so staging and
 * production refuse to start without it (docs/DEPLOY.md). `local` and `test` keep working with the empty default.
 */
@Configuration(proxyBeanMethods = false)
public class TrustedProxyConfiguration {
    static final String PROPERTY = "server.tomcat.remoteip.internal-proxies";

    @Bean @Profile({"staging", "prod"})
    InitializingBean trustedProxyGuard(Environment environment) {
        return () -> {
            if (environment.getProperty(PROPERTY, "").isBlank()) {
                throw new IllegalStateException("TRUSTED_PROXY_PATTERN is empty: set it to the Caddy peers' address pattern (docs/DEPLOY.md), "
                        + "or every client shares the proxy's address in the rate limits and the consent ipHash");
            }
        };
    }
}
