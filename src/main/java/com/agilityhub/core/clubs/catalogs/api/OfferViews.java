package com.agilityhub.core.clubs.catalogs.api;

import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.catalogs.domain.*;
import com.agilityhub.core.clubs.catalogs.persistence.Plan;
import com.agilityhub.core.clubs.catalogs.persistence.Price;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditQuery;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;

@Component
public class OfferViews {
    private final PlanService plans;
    private final PriceService prices;
    private final CatalogViews catalogs;
    private final AuditQuery audit;
    private final ObjectMapper mapper;
    private final PriceLineFormatter formatter;
    public OfferViews(PlanService plans, PriceService prices, CatalogViews catalogs, AuditQuery audit, ObjectMapper mapper, PriceLineFormatter formatter) {
        this.plans = plans; this.prices = prices; this.catalogs = catalogs; this.audit = audit; this.mapper = mapper; this.formatter = formatter;
    }
    Map<String, Object> input(Object request) {
        var values = catalogs.input(request);
        if (request instanceof CatalogRequests.PricePatch patch && patch.validTo() != null) {
            if (!patch.validTo().isNull() && !patch.validTo().isTextual()) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            values.put("validTo", patch.validTo().isNull() ? null : patch.validTo().asText());
        }
        return values;
    }
    private boolean billing() { return plans.config().modules().contains(Module.BILLING); }
    private <T> T map(Object value, Class<T> type) { return mapper.convertValue(value, type); }
    private String text(LocalizedText text, Locale locale) { return text == null ? null : text.withDefaultLocale(plans.config().club().defaultLocale()).resolve(locale).value(); }
    private Map<String, String> translations(LocalizedText text, boolean full) { return text == null || !full ? null : text.values(); }
    private LastChange last(String type, String id, boolean full) {
        if (!full) { return null; }
        var value = audit.lastChange(type, id); return value == null ? null : new LastChange(value.at(), value.actorName(), value.action().name());
    }
    private CatalogResponses.PlanTexts texts(Plan plan, Locale locale, boolean full) {
        var values = plan.texts();
        return new CatalogResponses.PlanTexts(text(values.description(), locale), translations(values.description(), full),
                text(values.offerLabel(), locale), translations(values.offerLabel(), full), text(values.priceLabel(), locale), translations(values.priceLabel(), full));
    }
    private List<Price> current(Plan plan) {
        return prices.list(plan.id(), null).stream().filter(price -> PriceRules.supports(plan.type(), price.concept()) && PriceRules.status(price, prices.today()) == PriceRules.Status.CURRENT).toList();
    }
    private String priceLine(Plan plan, Locale locale, List<Price> current) {
        var concept = switch (plan.type()) {
            case MONTHLY -> OfferTerms.PriceConcept.MONTHLY_FEE;
            case PACK -> OfferTerms.PriceConcept.PACK;
            case SINGLE_CLASS -> OfferTerms.PriceConcept.SINGLE_CLASS;
        };
        Money amount = current.stream().filter(price -> price.concept() == concept).map(Price::amount).findFirst().orElse(null);
        return formatter.line(plan.type(), amount, plan.pack() == null ? null : plan.pack().validityMonths(), text(plan.texts().priceLabel(), locale), locale);
    }
    CatalogResponses.Price price(String id) { return price(prices.get(id), true); }
    private CatalogResponses.Price price(Price item, boolean full) {
        return new CatalogResponses.Price(item.id(), item.planId(), map(item.concept(), CatalogResponses.PriceConcept.class), item.amount(), item.taxPercent(),
                item.validFrom(), item.validTo(), map(PriceRules.status(item, prices.today()), CatalogResponses.PriceStatus.class),
                item.concept() == OfferTerms.PriceConcept.MONTHLY_FEE || item.concept() == OfferTerms.PriceConcept.MAINTENANCE_FEE
                        ? CatalogResponses.PricePeriodicity.MONTHLY : CatalogResponses.PricePeriodicity.ONE_OFF, prices.locked(item), last("Price", item.id(), full), item.version());
    }
    CatalogItems<CatalogResponses.Price> prices(String plan, CatalogResponses.PriceConcept concept) {
        var items = prices.list(plan, map(concept, OfferTerms.PriceConcept.class)).stream().map(item -> price(item, true)).toList();
        return new CatalogItems<>(items, items.size());
    }
    CatalogResponses.Plan plan(String id, boolean detail, boolean warnings) { return plan(plans.get(id), detail, warnings); }
    private CatalogResponses.Plan plan(Plan plan, boolean detail, boolean warnings) {
        boolean full = catalogs.admin(); var locale = LocaleContext.current();
        var current = billing() ? current(plan) : List.<Price>of();
        var usage = full ? map(plans.usage(plan.id()), CatalogResponses.PlanUsage.class) : null;
        return new CatalogResponses.Plan(plan.id(), plan.code(), text(plan.name(), locale), translations(plan.name(), full),
                map(plan.type(), CatalogResponses.PlanType.class), map(plan.billingMode(), CatalogResponses.BillingMode.class), plan.dogsIncluded(),
                billing() ? priceLine(plan, locale, current) : null, billing() ? map(plan.entryFee(), CatalogResponses.EntryFee.class) : null,
                map(plan.pack(), CatalogResponses.PackSettings.class), map(plan.singleClass(), CatalogResponses.SingleClassSettings.class),
                text(plan.conditions(), locale), translations(plan.conditions(), full), texts(plan, locale, full), plan.showOnSignup(), plan.showOnWeb(), plan.order(), plan.active(),
                billing() ? current.stream().map(item -> price(item, full)).toList() : null,
                billing() && detail && full ? prices.list(plan.id(), null).stream().map(item -> price(item, true)).toList() : null,
                usage, billing() ? plans.entryFee(plan) : null, warnings && !plan.active() ? usage : null, last("Plan", plan.id(), full), plan.version());
    }
    CatalogItems<CatalogResponses.Plan> plans(boolean inactive) {
        catalogs.checkInactive(inactive); var items = plans.list(inactive).stream().map(item -> plan(item, false, false)).toList();
        return new CatalogItems<>(items, items.size());
    }
    CatalogResponses.PublicPlans publicPlans(Locale locale) {
        var club = plans.config().club();
        var items = plans.list(false).stream().filter(plan -> plan.showOnWeb() && plans.offered(plan)).map(plan -> {
            var current = billing() ? current(plan) : List.<Price>of();
            return new CatalogResponses.PublicPlan(plan.id(), plan.code(), text(plan.name(), locale), plan.name().values(),
                    map(plan.type(), CatalogResponses.PlanType.class), map(plan.billingMode(), CatalogResponses.BillingMode.class), plan.dogsIncluded(),
                    billing() ? priceLine(plan, locale, current) : null, text(plan.conditions(), locale), translations(plan.conditions(), true),
                    map(plan.pack(), CatalogResponses.PackSettings.class), map(plan.singleClass(), CatalogResponses.SingleClassSettings.class), texts(plan, locale, true),
                    billing() ? current.stream().map(price -> new CatalogResponses.PublicPrice(map(price.concept(), CatalogResponses.PriceConcept.class), price.amount(), price.taxPercent())).toList() : null,
                    billing() ? plans.entryFee(plan) : null, plan.order());
        }).toList();
        return new CatalogResponses.PublicPlans(new CatalogResponses.PublicPlansClub(club.slug(), club.name(), club.currency()), items);
    }
}
