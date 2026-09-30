package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.NotificationDispatcher;
import com.agilityhub.core.clubs.messaging.application.engine.NotificationEmailRenderer;
import com.agilityhub.core.clubs.messaging.application.engine.NotificationEngine;
import com.agilityhub.core.clubs.messaging.application.engine.NotificationEventHandler;
import com.agilityhub.core.clubs.messaging.application.engine.RecipientResolver;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateProvider;
import com.agilityhub.core.clubs.messaging.application.engine.UnsubscribeTokens;
import com.agilityhub.core.clubs.messaging.application.integrations.AllowListSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.FakePushSender;
import com.agilityhub.core.clubs.messaging.application.integrations.FakeSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.LogSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.PushSender;
import com.agilityhub.core.clubs.messaging.application.integrations.SmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.TwilioSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.WebPushSender;
import com.agilityhub.core.clubs.messaging.application.ports.BookingRelevancePort;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.SignupContactPort;
import com.agilityhub.core.clubs.messaging.application.ports.StaffDirectoryPort;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscriptionRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.platform.application.ClubSmsUsage;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * S11 WP-11-B wiring (E7-T02): the providers, selected like the `EmailSender` (decision E13) — staging/prod refuse to start
 * without their credentials, `test` gets the in-memory doubles, `local` without credentials the log sender — the engine,
 * the dispatcher and one outbox consumer per catalog event type, registered programmatically with the durable names
 * `notifications.<EventType>`.
 *
 * <p>SMS: `TwilioSmsSender` with `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` (and the optional `TWILIO_MESSAGING_SERVICE_SID`);
 * outside `prod` it is wrapped by the allow-list guard of `SMS_ALLOWED_NUMBERS` (organizer 2026-09-24). Push:
 * `WebPushSender` with `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`; `test`/`local` without keys use the fake.
 * Unsubscribe links: `EMAIL_UNSUBSCRIBE_KEY` (32 bytes base64), random in `local`/`test`.</p>
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfiguration {
    private static final Profiles STRICT = Profiles.of("staging", "prod");

    /** One `notifications.<EventType>` handler per consumed event type (never rename: bean names are consumer ids). */
    @Bean static BeanDefinitionRegistryPostProcessor notificationEventHandlers() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
                for (String type : NotificationEventHandler.eventTypes()) {
                    // The event type is explicit; the engine's ObjectProvider is autowired (constructor mode).
                    var definition = BeanDefinitionBuilder.genericBeanDefinition(NotificationEventHandler.class).addConstructorArgValue(type)
                            .setAutowireMode(org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR).getBeanDefinition();
                    registry.registerBeanDefinition(NotificationEventHandler.PREFIX + type, definition);
                }
            }
            @Override public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) { }
        };
    }

    @Bean SmsSender smsSender(Environment environment, ObjectMapper mapper, @Value("${notifications.sms.twilio-account-sid:}") String sid,
            @Value("${notifications.sms.twilio-auth-token:}") String token, @Value("${notifications.sms.twilio-messaging-service-sid:}") String service,
            @Value("${notifications.sms.allowed-numbers:}") String allowed) {
        if (environment.acceptsProfiles(STRICT)) {
            if (sid.isBlank() || token.isBlank()) { throw new IllegalStateException("TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN are required in staging/prod"); }
        } else if (environment.acceptsProfiles(Profiles.of("test"))) { return new FakeSmsSender(); }
        else if (sid.isBlank() && environment.acceptsProfiles(Profiles.of("local"))) { return new LogSmsSender(); }
        if (sid.isBlank() || token.isBlank()) { throw new IllegalStateException("TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN are required outside local/test"); }
        var twilio = new TwilioSmsSender(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), mapper, URI.create("https://api.twilio.com"),
                sid, token, service, Duration.ofSeconds(10));
        return environment.acceptsProfiles(Profiles.of("prod")) ? twilio : new AllowListSmsSender(twilio, AllowListSmsSender.parse(allowed));
    }

    @Bean PushSender pushSender(Environment environment, ObjectMapper mapper, Clock clock, ClubConfigService configs,
            @Value("${notifications.push.vapid-public-key:}") String publicKey, @Value("${notifications.push.vapid-private-key:}") String privateKey,
            @Value("${notifications.push.vapid-subject:}") String subject) {
        boolean keys = !publicKey.isBlank() && !privateKey.isBlank() && !subject.isBlank();
        if (!keys) {
            if (environment.acceptsProfiles(STRICT)) { throw new IllegalStateException("VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY and VAPID_SUBJECT are required in staging/prod"); }
            if (environment.acceptsProfiles(Profiles.of("test", "local"))) { return new FakePushSender(publicKey); }
            throw new IllegalStateException("VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY and VAPID_SUBJECT are required outside local/test");
        }
        if (environment.acceptsProfiles(Profiles.of("test"))) { return new FakePushSender(publicKey); }
        return new WebPushSender(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), mapper, clock, Duration.ofSeconds(10), publicKey, privateKey,
                subject, () -> 60 * Objects.requireNonNullElse(configs.get(TenantContext.require()).get("messaging.push.ttlMinutes", Integer.class), 1440));
    }

    @Bean UnsubscribeTokens unsubscribeTokens(Environment environment, Clock clock, @Value("${notifications.unsubscribe-key:}") String key) {
        if (key.isBlank()) {
            if (environment.acceptsProfiles(STRICT)) { throw new IllegalStateException("EMAIL_UNSUBSCRIBE_KEY is required in staging/prod"); }
            return UnsubscribeTokens.random(clock);
        }
        try { return new UnsubscribeTokens(Base64.getDecoder().decode(key.strip()), clock); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("EMAIL_UNSUBSCRIBE_KEY must be 32 bytes in base64"); }
    }

    @Bean NotificationEmailRenderer notificationEmailRenderer(IcuMessageSource messages) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML); resolver.setCharacterEncoding("UTF-8");
        var engine = new TemplateEngine(); engine.setTemplateResolver(resolver);
        return new NotificationEmailRenderer(messages, engine);
    }

    /** S11 §8: the product seed of the club templates (`seed/message-templates.{ca,es,en}.json`), checked against the catalog at start-up. */
    @Bean MessageTemplateSeed messageTemplateSeed(ObjectMapper mapper) { return MessageTemplateSeed.load(mapper); }

    @Bean TemplateProvider notificationTemplates(MessageTemplateRepository templates, MessageTemplateSeed seeds, Clock clock, PlatformTransactionManager transactions) {
        return new TemplateProvider(templates, seeds, clock, transactions);
    }

    @Bean RecipientResolver notificationRecipients(MemberDirectoryPort members, StaffDirectoryPort staff, SignupContactPort signups) {
        return new RecipientResolver(members, staff, signups);
    }

    @Bean NotificationDispatcher notificationDispatcher(NotificationRepository notifications, PushSubscriptionRepository subscriptions, EmailSender email,
            SmsSender sms, PushSender push, ClubConfigService configs, ClubEmailSettings emailSettings, ClubSmsUsage usage, NotificationEmailRenderer emails,
            UnsubscribeTokens unsubscribes, MemberDirectoryPort members, NotificationAccounts accounts, EventPublisher events, PlatformTransactionManager transactions,
            List<NotificationFactsPort> owners, Clock clock, @Value("${email.platform-from:}") String from) {
        return new NotificationDispatcher(notifications, subscriptions, email, sms, push, configs, emailSettings, usage, emails, unsubscribes, members, accounts, events,
                new TransactionTemplate(transactions), owners, clock, from.isBlank() ? "no-reply@example.test" : from);
    }

    @Bean NotificationEngine notificationEngine(ClubConfigService configs, IcuMessageSource messages, TemplateProvider templates, RecipientResolver recipients,
            NotificationRepository notifications, PushSubscriptionRepository subscriptions, NotificationAccounts accounts, List<NotificationFactsPort> owners,
            BookingRelevancePort bookings, EventPublisher events, NotificationDispatcher dispatcher, Clock clock, PlatformTransactionManager transactions) {
        return new NotificationEngine(configs, messages, templates, recipients, notifications, subscriptions, accounts, owners, bookings, events, dispatcher,
                clock, transactions);
    }
}
