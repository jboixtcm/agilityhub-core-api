package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.persistence.S3MigrationReportStore;
import com.agilityhub.core.migration.persistence.VolumeMigrationReportStore;
import com.agilityhub.core.shared.application.PrivateFileStorageConfiguration;
import com.agilityhub.core.shared.application.PrivateFileStorageSettings;
import java.nio.file.Path;
import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods=false)
@Import(PrivateFileStorageConfiguration.class)
public class MigrationReportConfiguration {
    @Bean @Profile("staging | prod") @ConditionalOnProperty(name = "core.storage.mode", havingValue = "s3", matchIfMissing = true)
    MigrationReportStore migrationReportStore(S3Client client, S3Presigner signer, Environment environment, Clock clock) {
        return new S3MigrationReportStore(client,signer,environment.getRequiredProperty("exports.s3.bucket"),clock);
    }
    @Bean @Profile("staging | prod") @ConditionalOnProperty(name = "core.storage.mode", havingValue = "filesystem")
    MigrationReportStore volumeMigrationReportStore(PrivateFileStorageSettings settings, Clock clock) {
        return new VolumeMigrationReportStore(settings, clock);
    }
    @Bean @Profile("!staging & !prod")
    MigrationReportStore unavailableMigrationReportStore() {
        return new MigrationReportStore() {
            public void requireReady() { throw new IllegalStateException("Production migration reports require configured private S3 storage"); }
            public void put(String key,Path file,Instant expiresAt) { requireReady(); }
            public String downloadUrl(String key,Instant expiresAt) { requireReady(); return null; }
            public void delete(String key) { requireReady(); }
        };
    }
}
