package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TransactionRetries;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S11 R-11-01: the club's `MessageTemplate{clubId, code}`, or — for a club created before the code existed — the product
 * seed, stored on first use. The seed is {@link MessageTemplateSeed} (`seed/message-templates.{ca,es,en}.json`, E7-T03) in
 * the club's languages (a club stores its own locales: the recipient of another language reads the club's default, R-11-01),
 * with the catalog's default matrix, icon and colour; `customized = false`. The first-use insert commits in its own
 * transaction, so a concurrent creator (the unique `{clubId, code}`) never aborts the engine's transaction: the loser reads
 * the winner's document.
 */
public class TemplateProvider {
    static final String SYSTEM_ACTOR = "system:notification-engine";
    static final int FIRST_USE_ATTEMPTS = 8;
    private final MessageTemplateRepository templates; private final MessageTemplateSeed seeds; private final Clock clock;
    private final TransactionTemplate own, outside;

    public TemplateProvider(MessageTemplateRepository templates, MessageTemplateSeed seeds, Clock clock, PlatformTransactionManager transactions) {
        this.templates = templates; this.seeds = seeds; this.clock = clock;
        this.own = new TransactionTemplate(transactions); own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.outside = new TransactionTemplate(transactions); outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** A template of the current club by id (R-11-13: the one an announcement was sent with); another club's is absent. */
    public java.util.Optional<MessageTemplate> byId(String id) { return id == null ? java.util.Optional.empty() : templates.findById(id); }

    /** The template of a templated code in the current club, created from the seed (every product language) when absent. */
    public MessageTemplate forCode(NotificationSpec spec, String clubDefaultLocale) { return forCode(spec, null, clubDefaultLocale); }
    /**
     * The template of a templated code in the current club, created from the seed in the club's `locales` when absent. A
     * concurrent first use of the code (a hundred reminders at once, T-11-31) loses on the unique `{clubId, code}`: with a
     * duplicate key once the winner committed, with a `WriteConflict` (112) or a `TransientTransactionError` while it had not.
     * Either way the loser reads the winner's template outside any transaction (the caller's snapshot predates it) and, while
     * it is not committed yet, waits 50–150 ms and tries again, at most {@value #FIRST_USE_ATTEMPTS} times.
     */
    public MessageTemplate forCode(NotificationSpec spec, Collection<String> clubLocales, String clubDefaultLocale) {
        var existing = templates.findByCode(spec.code());
        if (existing.isPresent()) { return existing.get(); }
        var seed = seed(spec, clubLocales, clubDefaultLocale);
        for (int attempt = 1; ; attempt++) {
            try { return own.execute(tx -> templates.insert(seed)); }
            catch (RuntimeException raced) {
                if (!TransactionRetries.conflict(raced)) { throw raced; }
                var winner = outside.execute(tx -> templates.findByCode(spec.code()));
                if (winner.isPresent()) { return winner.get(); }
                if (attempt >= FIRST_USE_ATTEMPTS) { throw raced; }
                pause();
            }
        }
    }

    /**
     * D9's list (R-11-01): every eligible code of the club has its template afterwards. The missing ones are inserted
     * together in their own transaction; when a concurrent first use of one of them wins (a duplicate key or a write
     * conflict), each code goes through {@link #forCode} instead.
     */
    public void ensureAll(Collection<String> clubLocales, String clubDefaultLocale) {
        var present = new java.util.HashSet<String>();
        templates.findAll().forEach(template -> { if (template.code() != null) { present.add(template.code()); } });
        var missing = new ArrayList<MessageTemplate>();
        for (var spec : MessageTemplateSeed.eligible()) { if (!present.contains(spec.code())) { missing.add(seed(spec, clubLocales, clubDefaultLocale)); } }
        if (missing.isEmpty()) { return; }
        try { own.executeWithoutResult(tx -> missing.forEach(templates::insert)); }
        catch (RuntimeException raced) {
            if (!TransactionRetries.conflict(raced)) { throw raced; }
            MessageTemplateSeed.eligible().forEach(spec -> forCode(spec, clubLocales, clubDefaultLocale));
        }
    }
    private static void pause() {
        try { Thread.sleep(TransactionRetries.jitter()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    /** The product seed of a templated code for the current club: every product language. */
    public MessageTemplate seed(NotificationSpec spec, String clubDefaultLocale) { return seed(spec, null, clubDefaultLocale); }
    /** The product seed of a templated code for the current club in its languages (also D9's «Restaura el text per defecte»). */
    public MessageTemplate seed(NotificationSpec spec, Collection<String> clubLocales, String clubDefaultLocale) {
        if (!spec.templated()) { throw new IllegalArgumentException(spec.code() + " is a SYSTEM code without template"); }
        var seeded = seeds.of(spec.code()).orElseThrow(() -> new IllegalStateException("No template seed for " + spec.code()));
        String defaultLocale = clubDefaultLocale == null ? "ca" : clubDefaultLocale;
        var now = clock.instant();
        return new MessageTemplate(null, TenantContext.require(), spec.code(), TemplateKind.CATALOG, spec.category(), seeded.title(clubLocales, defaultLocale),
                seeded.body(clubLocales, defaultLocale), seeded.smsBody(clubLocales, defaultLocale), seeded.icon(), seeded.color(), seeded.matrix(), true,
                spec.mandatory(), false, TemplateStatus.ACTIVE, null, now, SYSTEM_ACTOR, now, SYSTEM_ACTOR);
    }

    public MessageTemplateSeed seeds() { return seeds; }
}
