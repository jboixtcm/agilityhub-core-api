package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S11 R-11-01: the club's `MessageTemplate{clubId, code}`, or — for a club created before the code existed — the product
 * seed, stored on first use. The seed is the product copy `notif.{code}.title|body|sms` of every product language
 * (`messages_*`), the catalog's default matrix, icon and colour; `customized = false`. The first-use insert commits in its
 * own transaction, so a concurrent creator (the unique `{clubId, code}`) never aborts the engine's transaction: the loser
 * reads the winner's document.
 */
public class TemplateProvider {
    static final String SYSTEM_ACTOR = "system:notification-engine";
    private final MessageTemplateRepository templates; private final IcuMessageSource messages; private final Clock clock;
    private final TransactionTemplate own, outside;

    public TemplateProvider(MessageTemplateRepository templates, IcuMessageSource messages, Clock clock, PlatformTransactionManager transactions) {
        this.templates = templates; this.messages = messages; this.clock = clock;
        this.own = new TransactionTemplate(transactions); own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.outside = new TransactionTemplate(transactions); outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** The template of a templated code in the current club, created from the seed when absent. */
    public MessageTemplate forCode(NotificationSpec spec, String clubDefaultLocale) {
        var existing = templates.findByCode(spec.code());
        if (existing.isPresent()) { return existing.get(); }
        var seed = seed(spec, clubDefaultLocale);
        try {
            return own.execute(tx -> templates.insert(seed));
        } catch (DuplicateKeyException raced) {
            return outside.execute(tx -> templates.findByCode(spec.code())).orElseThrow(() -> raced);
        }
    }

    /** The product seed of a templated code for the current club (also E7-T03's «Restaura el text per defecte»). */
    public MessageTemplate seed(NotificationSpec spec, String clubDefaultLocale) {
        if (!spec.templated()) { throw new IllegalArgumentException(spec.code() + " is a SYSTEM code without template"); }
        String defaultLocale = clubDefaultLocale == null ? "ca" : clubDefaultLocale;
        var title = text("notif." + spec.code() + ".title", defaultLocale).orElseThrow(() -> new IllegalStateException("No product copy for " + spec.code()));
        var body = text("notif." + spec.code() + ".body", defaultLocale).orElseThrow(() -> new IllegalStateException("No product copy for " + spec.code()));
        var sms = text("notif." + spec.code() + ".sms", defaultLocale).orElse(null);
        var now = clock.instant();
        return new MessageTemplate(null, TenantContext.require(), spec.code(), TemplateKind.CATALOG, spec.category(), title, body, sms, spec.icon(),
                spec.color(), spec.defaultMatrix(), true, spec.mandatory(), false, TemplateStatus.ACTIVE, null, now, SYSTEM_ACTOR, now, SYSTEM_ACTOR);
    }

    /** The product copy of a key in every product language that has it. */
    Optional<LocalizedText> text(String key, String defaultLocale) {
        var values = new LinkedHashMap<String, String>();
        for (Locale locale : messages.supportedLocales()) {
            String pattern = messages.patternIn(key, locale);
            if (pattern != null && !pattern.isBlank()) { values.put(locale.getLanguage(), pattern); }
        }
        if (values.isEmpty()) { return Optional.empty(); }
        return Optional.of(new LocalizedText(values, values.containsKey(defaultLocale) ? defaultLocale : values.keySet().iterator().next()));
    }
}
