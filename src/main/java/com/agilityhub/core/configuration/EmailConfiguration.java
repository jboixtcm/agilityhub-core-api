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
            @Value("${email.sendgrid.api-key:}") String key, @Value("${email.platform-from:}") String from,
            @Value("${email.local-mailbox-directory:}") String mailbox, BrevoWebhookRepository brevoState) {
        var demo = HostedDemoPolicy.from(environment);
        String provider = provider(environment);
        if (!HostedDemoPolicy.emailTransportEnabled(environment)) { return new DisabledEmailSender(); }
        String selectedKey = provider.equals("brevo") ? environment.getProperty("email.brevo.api-key", "") : key;
        String variable = provider.equals("brevo") ? "BREVO_API_KEY" : "SENDGRID_API_KEY";
        if (environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
            if (selectedKey.isBlank()) { throw new IllegalStateException(variable + " is required in staging/prod"); }
            if (from.isBlank()) { throw new IllegalStateException("MAIL_FROM_PLATFORM is required in staging/prod"); }
        } else if (environment.acceptsProfiles(Profiles.of("test"))) { return new FakeEmailSender(); }
        else if (selectedKey.isBlank() && environment.acceptsProfiles(Profiles.of("local"))) {
            return new LogEmailSender(mailbox.isBlank() ? null : java.nio.file.Path.of(mailbox), mapper);
        }
        if (selectedKey.isBlank()) { throw new IllegalStateException(variable + " is required outside local/test"); }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        EmailSender sender = provider.equals("brevo")
                ? new BrevoEmailSender(client, mapper, URI.create("https://api.brevo.com/v3/smtp/email"), selectedKey, Duration.ofSeconds(10),
                        from, environment.getProperty("email.platform-from-name", ""), brevoState::suppressed)
                : new SendGridEmailSender(client, mapper, URI.create("https://api.sendgrid.com/v3/mail/send"), key, Duration.ofSeconds(10));
        if (!HostedDemoPolicy.emailDeliveryEnabled(environment)) { return new RecoveryOnlyEmailSender(sender); }
        return demo.enabled() ? new AllowListEmailSender(sender, demo) : sender;
    }
    @Bean SendGridSignatureVerifier sendGridSignatures(Environment environment, @Value("${email.sendgrid.webhook-public-key:}") String key) {
        HostedDemoPolicy.from(environment);
        if (HostedDemoPolicy.emailTransportEnabled(environment) && provider(environment).equals("sendgrid") && key.isBlank() && environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
            throw new IllegalStateException("SENDGRID_WEBHOOK_PUBLIC_KEY is required in staging/prod");
        }
        return new SendGridSignatureVerifier(key);
    }
    @Bean BrevoWebhookAuthentication brevoWebhookAuthentication(Environment environment) {
        HostedDemoPolicy.from(environment);
        String token = environment.getProperty("email.brevo.webhook-token", "");
        if ((HostedDemoPolicy.emailTransportEnabled(environment) && provider(environment).equals("brevo")
                && environment.acceptsProfiles(Profiles.of("staging", "prod")) || !token.isBlank())
                && !token.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalStateException("BREVO_WEBHOOK_TOKEN must contain 32 to 256 random URL-safe characters in staging/prod");
        }
        return new BrevoWebhookAuthentication(token);
    }
    private static String provider(Environment environment) {
        String provider = environment.getProperty("email.provider", "sendgrid");
        if (!java.util.Set.of("sendgrid", "brevo").contains(provider)) { throw new IllegalStateException("EMAIL_PROVIDER must be sendgrid or brevo"); }
        return provider;
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
            NotificationAccounts accounts, EventPublisher events, PlatformTransactionManager manager, Clock clock,
            com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort members,
            com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort writer) {
        return new SendGridWebhookService(notifications, receipts, accounts, events, new TransactionTemplate(manager), clock, members, writer);
    }
    @Bean ApplicationRunner emailCollections(MongoTemplate mongo) {
        return args -> {
            // Collections must exist before their first transactional insert. The S11 §3 indexes of the three S11 collections
            // are the repositories' (E7-T01), on `recipient.accountId` instead of the E1 `accountId`.
            for (Class<?> type : java.util.List.of(Notification.class, SendGridWebhookReceipt.class, BrevoWebhookReceipt.class,
                    BrevoEmailSuppression.class, MessageTemplate.class, PushSubscription.class)) {
                if (!mongo.collectionExists(type)) { mongo.createCollection(type); }
            }
        };
    }
}
