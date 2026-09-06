package com.agilityhub.core.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.boot.autoconfigure.mongo.PropertiesMongoConnectionDetails;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MongoConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"staging", "prod"})
    void E0_T01_deploymentProfilesUseEnvironmentCredentialsAndReplicaSet(String profile) {
        runner.withPropertyValues(
                "spring.profiles.active=" + profile,
                "MONGODB_HOST=database.example.test",
                "MONGODB_DATABASE=fixture_database",
                "MONGODB_USERNAME=fixture_user",
                "MONGODB_PASSWORD=fixture_password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
                    assertThat(context.getEnvironment().getProperty("security.eventRetentionDays")).isNull();
                    var connection = new PropertiesMongoConnectionDetails(
                            context.getBean(MongoProperties.class), null).getConnectionString();
                    assertThat(connection.getHosts()).containsExactly("database.example.test:27017");
                    assertThat(connection.getDatabase()).isEqualTo("fixture_database");
                    assertThat(connection.getRequiredReplicaSetName()).isEqualTo("rs0");
                    assertThat(connection.getCredential()).isNotNull();
                    assertThat(connection.getCredential().getUserName()).isEqualTo("fixture_user");
                    assertThat(connection.getCredential().getPassword()).containsExactly("fixture_password".toCharArray());
                    assertThat(connection.getCredential().getSource()).isEqualTo("admin");
                    assertThat(Binder.get(context.getEnvironment())
                            .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class)).get())
                            .noneMatch(exclusion -> exclusion.contains("Mongo"));
                    assertThat(context.getEnvironment().getProperty("spring.threads.virtual.enabled", Boolean.class))
                            .isTrue();
                });
    }

    @Test
    void E0_T01_testProfileKeepsMongoEnabledWithASeparateDatabase() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.path")).isEqualTo("/api/v1/openapi.json");
            var connection = new PropertiesMongoConnectionDetails(
                    context.getBean(MongoProperties.class), null).getConnectionString();
            assertThat(connection.getDatabase()).isEqualTo("agilityhub_test");
            assertThat(connection.getRequiredReplicaSetName()).isEqualTo("rs0");
            assertThat(Binder.get(context.getEnvironment())
                    .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class)).get())
                    .noneMatch(exclusion -> exclusion.contains("Mongo"));
        });
    }

    @Test
    void E0_T02_defaultLocalProfileEnablesTheDevelopmentReplicaSet() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getDefaultProfiles()).containsExactly("local");
            assertThat(Binder.get(context.getEnvironment())
                    .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class)).get())
                    .noneMatch(exclusion -> exclusion.contains("Mongo"));
            var connection = new PropertiesMongoConnectionDetails(
                    context.getBean(MongoProperties.class), null).getConnectionString();
            assertThat(connection.getHosts()).containsExactly("localhost:27017");
            assertThat(connection.getDatabase()).isEqualTo("agilityhub");
            assertThat(connection.getRequiredReplicaSetName()).isEqualTo("rs0");
            assertThat(connection.isDirectConnection()).isTrue();
            assertThat(connection.getCredential()).isNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MongoProperties.class)
    static class MongoConfiguration {
    }
}
