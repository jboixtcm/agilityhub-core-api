package com.agilityhub.core.configuration;

import com.mongodb.MongoClientSettings;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MongoTimeoutConfigurationTest {
    @Test void E11_T03_everyDriverWaitIsFiniteAndConfigurable() {
        var settings = new MongoTimeoutConfiguration.Settings(Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(300), Duration.ofMillis(400), 7, Duration.ofMillis(500));
        var builder = MongoClientSettings.builder();
        new MongoTimeoutConfiguration().boundedMongoClient(settings).customize(builder);
        var result = builder.build();
        assertThat(result.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(100);
        assertThat(result.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(200);
        assertThat(result.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(300);
        assertThat(result.getTimeout(TimeUnit.MILLISECONDS)).as("no client-wide transaction deadline").isNull();
        assertThat(result.getConnectionPoolSettings().getMaxSize()).isEqualTo(7);
        assertThat(result.getConnectionPoolSettings().getMaxWaitTime(TimeUnit.MILLISECONDS)).isEqualTo(500);
    }
    @Test void E85_aClientWideTimeoutCannotSilentlyCapSeedsAndJobs() {
        var one = Duration.ofSeconds(1);
        var settings = new MongoTimeoutConfiguration.Settings(one, Duration.ZERO, one, one, 1, one);
        var customizer = new MongoTimeoutConfiguration().boundedMongoClient(settings);
        var unbounded = MongoClientSettings.builder();
        customizer.customize(unbounded);
        assertThat(unbounded.build().getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isZero();
        assertThatThrownBy(() -> customizer.customize(MongoClientSettings.builder().timeout(10, TimeUnit.MILLISECONDS)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeoutMS must be unset");
    }
    @Test void E11_T03_invalidBoundsCannotStart() {
        var one = Duration.ofSeconds(1);
        for (var invalid : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(1000)}) {
            assertThatThrownBy(() -> new MongoTimeoutConfiguration.Settings(invalid, one, one, one, 1, one))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new MongoTimeoutConfiguration.Settings(one, one, one, one, 0, one))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
