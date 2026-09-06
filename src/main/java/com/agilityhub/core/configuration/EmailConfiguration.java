package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.*;
import com.agilityhub.core.clubs.messaging.persistence.*;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

@Configuration(proxyBeanMethods = false)
public class EmailConfiguration {
    @Bean EmailSender emailSender(Environment environment, ObjectMapper mapper,
            @Value("${email.sendgrid.api-key:}") String key, @Value("${email.platform-from:}") String from) {
        if (environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
            if (key.isBlank()) { throw new IllegalStateException("SENDGRID_API_KEY is required in staging/prod"); }
            if (from.isBlank()) { throw new IllegalStateException("MAIL_FROM_PLATFORM is required in staging/prod"); }
        } else if (environment.acceptsProfiles(Profiles.of("test"))) { return new FakeEmailSender(); }
        else if (key.isBlank() && environment.acceptsProfiles(Profiles.of("local"))) { return new LogEmailSender(); }
        if (key.isBlank()) { throw new IllegalStateException("SENDGRID_API_KEY is required outside local/test"); }
        return new SendGridEmailSender(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), mapper,
                URI.create("https://api.sendgrid.com/v3/mail/send"), key, Duration.ofSeconds(10));
    }
    @Bean SendGridSignatureVerifier sendGridSignatures(Environment environment, @Value("${email.sendgrid.webhook-public-key:}") String key) {
        if (key.isBlank() && environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
            throw new IllegalStateException("SENDGRID_WEBHOOK_PUBLIC_KEY is required in staging/prod");
        }
        return new SendGridSignatureVerifier(key);
    }
    @Bean SystemEmailRenderer systemEmailRenderer(IcuMessageSource messages) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new TemplateEngine(); engine.setTemplateResolver(resolver);
        return new SystemEmailRenderer(messages, engine);
    }
    @Bean SystemNotificationService systemNotifications(NotificationAccounts accounts, ClubEmailSettings clubs, ParameterCatalog parameters,
            NotificationRepository notifications, EmailSender sender, SystemEmailRenderer renderer, EventPublisher events,
            PlatformTransactionManager manager, IcuMessageSource messages, Clock clock,
            @Value("${email.platform-from:}") String from) {
        return new SystemNotificationService(accounts, clubs, parameters, notifications, sender, renderer, events,
                new TransactionTemplate(manager), messages, clock, from.isBlank() ? "no-reply@example.test" : from);
    }
    @Bean SendGridWebhookService sendGridWebhooks(NotificationRepository notifications, SendGridWebhookReceiptRepository receipts,
            NotificationAccounts accounts, EventPublisher events, PlatformTransactionManager manager, Clock clock) {
        return new SendGridWebhookService(notifications, receipts, accounts, events, new TransactionTemplate(manager), clock);
    }
    @Bean ApplicationRunner emailCollections(MongoTemplate mongo) {
        return args -> {
            // Collections must exist before their first transactional insert.
            for (Class<?> type : java.util.List.of(Notification.class, SendGridWebhookReceipt.class)) {
                if (!mongo.collectionExists(type)) { mongo.createCollection(type); }
            }
            mongo.indexOps(Notification.class).ensureIndex(new org.springframework.data.mongodb.core.index.Index()
                    .on("clubId", org.springframework.data.domain.Sort.Direction.ASC)
                    .on("accountId", org.springframework.data.domain.Sort.Direction.ASC)
                    .on("createdAt", org.springframework.data.domain.Sort.Direction.DESC).named("notification_account_history"));
        };
    }
}
