package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.persistence.*;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
public class AttachmentConfiguration {
    @Bean @Profile("(local | test) & !staging & !prod")
    LocalAttachmentStorage localAttachments(Environment env, Clock clock) {
        return new LocalAttachmentStorage(Path.of(env.getProperty("attachments.local-directory", "./.local/attachments")), new java.security.SecureRandom().generateSeed(32), clock);
    }
    @Bean @Profile("staging | prod") AttachmentStorage s3Attachments(Environment env, Clock clock) {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(required(env, "access-key"), required(env, "secret-key")));
        var client = S3Client.builder().region(Region.of(required(env, "region"))).credentialsProvider(credentials)
                .overrideConfiguration(config -> config.apiCallTimeout(java.time.Duration.ofMinutes(2)));
        var signer = S3Presigner.builder().region(Region.of(required(env, "region"))).credentialsProvider(credentials);
        String endpoint = env.getProperty("attachments.s3.endpoint", "");
        if (!endpoint.isBlank()) { client.endpointOverride(java.net.URI.create(endpoint)); signer.endpointOverride(java.net.URI.create(endpoint)); }
        return new S3AttachmentStorage(client.build(), signer.build(), required(env, "bucket"), clock);
    }
    private String required(Environment env, String key) {
        String value = env.getProperty("attachments.s3." + key, "");
        if (value.isBlank()) { throw new IllegalStateException("Missing attachments.s3." + key); } return value;
    }
    @Bean org.springframework.boot.ApplicationRunner attachmentIndexes(AttachmentRepository repo) { return args -> repo.ensureIndexes(); }
    @Bean com.agilityhub.core.platform.application.audit.AuditableLoader attachmentAudit(AttachmentRepository repo) {
        return new com.agilityhub.core.platform.application.audit.AuditableLoader() {
            public String entityType() { return "Attachment"; }
            public Object load(String id) {
                return repo.findById(id).map(item -> java.util.Map.of("name", item.name(), "fileKey", item.fileKey(), "entityType", item.entityType(), "entityId", item.entityId())).orElse(null);
            }
        };
    }
}
