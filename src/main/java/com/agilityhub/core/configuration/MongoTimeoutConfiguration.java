package com.agilityhub.core.configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** E11-T03: finite driver waits also apply to API reads and to transactional retries. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MongoTimeoutConfiguration.Settings.class)
public class MongoTimeoutConfiguration {
    @ConfigurationProperties("core.mongo")
    public record Settings(Duration connectTimeout, Duration socketTimeout, Duration serverSelectionTimeout,
                           Duration operationTimeout, int maxPoolSize, Duration poolWaitTimeout) {
        public Settings {
            for (Duration value : new Duration[]{connectTimeout, socketTimeout, serverSelectionTimeout, operationTimeout, poolWaitTimeout}) {
                if (value == null || value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Mongo timeouts must be positive and finite milliseconds");
                }
            }
            if (maxPoolSize < 1) { throw new IllegalArgumentException("Mongo pool must have positive size"); }
        }
    }
    @Bean MongoClientSettingsBuilderCustomizer boundedMongoClient(Settings settings) {
        return builder -> builder
                .applyToSocketSettings(socket -> socket.connectTimeout((int) settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout((int) settings.socketTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(settings.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(pool -> pool.maxSize(settings.maxPoolSize())
                        .maxWaitTime(settings.poolWaitTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .timeout(settings.operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
