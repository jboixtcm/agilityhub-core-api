package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.persistence.S3MigrationReportStore;
import java.nio.file.Path;
import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods=false)
public class MigrationReportConfiguration {
    @Bean @Profile("staging | prod")
    MigrationReportStore migrationReportStore(S3Client client, S3Presigner signer, Environment environment, Clock clock) {
        return new S3MigrationReportStore(client,signer,environment.getRequiredProperty("exports.s3.bucket"),clock);
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
