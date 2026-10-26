package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.shared.application.PrivateFileStorageConfiguration;
import com.agilityhub.core.shared.application.PrivateFileStorageSettings;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@Import(PrivateFileStorageConfiguration.class)
public class ExportConfiguration {
    // A separate default scheduler prevents unqualified jobs (including the outbox) from
    // being assigned to the only available scheduler and sharing long export work.
    @Bean(name = "taskScheduler")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = "taskScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler defaultScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("scheduled-job-"); return scheduler;
    }
    @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler exportScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("export-worker-"); return scheduler;
    }
    @Bean ApplicationRunner exportIndexes(ListExportRepository jobs, ExportWorkRepository work) { return arguments -> { jobs.ensureIndexes(); work.ensureIndexes(); }; }
    @Bean @Profile("(local | test) & !staging & !prod")
    LocalExportStorage localExports(Environment env, Clock clock) {
        String configured = env.getProperty("exports.signing-key", "");
        byte[] key = configured.isBlank() ? new java.security.SecureRandom().generateSeed(32) : java.util.Base64.getDecoder().decode(configured);
        return new LocalExportStorage(Path.of(env.getProperty("exports.local-directory", "./.local/exports")), key, clock);
    }
    @Bean @Profile("staging | prod") @ConditionalOnProperty(name = "core.storage.mode", havingValue = "filesystem")
    LocalExportStorage volumeExports(PrivateFileStorageSettings settings, Clock clock) {
        return new LocalExportStorage(settings.directory("exports"), settings.signingKey(), clock, settings::requireReady);
    }
    @Bean @Profile("staging | prod") @ConditionalOnProperty(name = "core.storage.mode", havingValue = "s3", matchIfMissing = true)
    S3Client exportS3Client(Environment env) {
        var builder = S3Client.builder().region(region(env)).credentialsProvider(credentials(env))
                .overrideConfiguration(config -> config.apiCallTimeout(java.time.Duration.ofMinutes(2)));
        String endpoint = env.getProperty("exports.s3.endpoint", "");
        if (!endpoint.isBlank()) { builder.endpointOverride(java.net.URI.create(endpoint)); }
        return builder.build();
    }
    @Bean @Profile("staging | prod") @ConditionalOnProperty(name = "core.storage.mode", havingValue = "s3", matchIfMissing = true)
    S3Presigner exportS3Signer(Environment env) {
        var builder = S3Presigner.builder().region(region(env)).credentialsProvider(credentials(env));
        String endpoint = env.getProperty("exports.s3.endpoint", "");
        if (!endpoint.isBlank()) { builder.endpointOverride(java.net.URI.create(endpoint)); }
        return builder.build();
    }
    @Bean @Profile("staging | prod") @ConditionalOnProperty(name = "core.storage.mode", havingValue = "s3", matchIfMissing = true)
    ExportStorage s3Exports(S3Client client, S3Presigner signer, Environment env, Clock clock) {
        return new S3ExportStorage(client, signer, required(env, "exports.s3.bucket"), clock);
    }
    private Region region(Environment env) { return Region.of(required(env, "exports.s3.region")); }
    private AwsCredentialsProvider credentials(Environment env) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(required(env, "exports.s3.access-key"), required(env, "exports.s3.secret-key")));
    }
    private String required(Environment env, String name) {
        String value = env.getProperty(name, "");
        if (value.isBlank()) { throw new IllegalStateException("Missing deployment configuration: " + name); }
        return value;
    }
}
