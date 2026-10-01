package com.agilityhub.core.configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/** E11-T03 / E85: HTTP deadlines must not become the lifetime of background transactions. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MongoTimeoutConfiguration.Settings.class)
public class MongoTimeoutConfiguration {
    private static final ThreadLocal<Long> REQUEST_TIMEOUT = new ThreadLocal<>();

    @ConfigurationProperties("core.mongo")
    public record Settings(Duration connectTimeout, Duration socketTimeout, Duration serverSelectionTimeout,
                           Duration operationTimeout, int maxPoolSize, Duration poolWaitTimeout) {
        public Settings {
            for (Duration value : new Duration[]{connectTimeout, serverSelectionTimeout, operationTimeout, poolWaitTimeout}) {
                if (value == null || value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Mongo timeouts must be positive and finite milliseconds");
                }
            }
            if (socketTimeout == null || socketTimeout.isNegative() || socketTimeout.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Mongo background socket timeout must be nonnegative and finite milliseconds");
            }
            if (maxPoolSize < 1) { throw new IllegalArgumentException("Mongo pool must have positive size"); }
        }
    }
    @Bean MongoClientSettingsBuilderCustomizer boundedMongoClient(Settings settings) {
        return builder -> {
            if (builder.build().getTimeout(TimeUnit.MILLISECONDS) != null) {
                throw new IllegalArgumentException("Mongo URI/client timeoutMS must be unset; configure the HTTP operation budgets instead");
            }
            builder
                .applyToSocketSettings(socket -> socket.connectTimeout((int) settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout((int) settings.socketTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(settings.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(pool -> pool.maxSize(settings.maxPoolSize())
                        .maxWaitTime(settings.poolWaitTimeout().toMillis(), TimeUnit.MILLISECONDS));
            // Do not set a client timeout: sessions would inherit it for the entire seed/job transaction.
        };
    }

    @Bean static BeanPostProcessor requestScopedMongoDatabaseFactory() {
        // Decorate Boot's factory after creation so its connection, credentials, converters and lifecycle stay intact.
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof MongoDatabaseFactory factory
                        ? new RequestScopedMongoDatabaseFactory(factory, REQUEST_TIMEOUT::get) : bean;
            }
        };
    }

    @Bean FilterRegistrationBean<OncePerRequestFilter> mongoRequestDeadline(Settings settings) {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                String path = new org.springframework.web.util.UrlPathHelper().getPathWithinApplication(request);
                boolean longOperation = path.matches("/api/v1/(?:.*/)?(?:export|data-export)")
                        || path.matches("/api/v1/exports/[^/]+/download")
                        || path.matches("/api/v1/(?:platform/clubs/[^/]+/)?jobs/[^/]+/trigger");
                Long previous = REQUEST_TIMEOUT.get();
                // E85: manual jobs are the same work as scheduled jobs; exports also have no HTTP deadline.
                if (longOperation) { REQUEST_TIMEOUT.remove(); }
                else { REQUEST_TIMEOUT.set(settings.operationTimeout().toMillis()); }
                try { chain.doFilter(request, response); }
                finally { if (previous == null) { REQUEST_TIMEOUT.remove(); } else { REQUEST_TIMEOUT.set(previous); } }
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        return registration;
    }
}
