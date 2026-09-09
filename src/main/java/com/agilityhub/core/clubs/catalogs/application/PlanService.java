package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.*;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;

@Service
public class PlanService {
    private final PlanRepository plans;
    private final PriceRepository prices;
    private final OfferWriter writer;
    private final OfferUsage usage;
    private final PlanValidator validator;
    private final IdentityTransactions transactions;
    private final ClubConfigService configs;
    private final AuditActorProvider actors;
    private final Clock clock;
    public PlanService(PlanRepository plans, PriceRepository prices, OfferWriter writer, OfferUsage usage, PlanValidator validator,
            IdentityTransactions transactions, ClubConfigService configs, AuditActorProvider actors, Clock clock) {
        this.plans = plans; this.prices = prices; this.writer = writer; this.usage = usage; this.validator = validator;
        this.transactions = transactions; this.configs = configs; this.actors = actors; this.clock = clock;
    }
    public ClubConfig config() { return configs.get(TenantContext.require()); }
    public Plan get(String id) { return plans.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public List<Plan> list(boolean inactive) {
        return plans.findAll().stream().filter(plan -> inactive || plan.active()).sorted(Comparator.comparingInt(Plan::order).thenComparing(Plan::id)).toList();
    }
    public Map<String, Long> usage(String id) { return usage.plan(id); }
    public boolean offered(Plan plan) {
        return plan.active() && switch (plan.type()) {
            case MONTHLY -> true;
            case PACK -> config().modules().contains(Module.PACKS);
            case SINGLE_CLASS -> config().modules().contains(Module.SINGLE_CLASS);
        };
    }
    public Money entryFee(Plan plan) {
        return EntryFeeCalculator.perDog(plan.entryFee(), config().get("billing.entryFeePerDog", Money.class), config().modules().contains(Module.BILLING));
    }
    public Money familySuggestion(Money firstDogFee, int dogs) {
        return EntryFeeCalculator.familySuggestion(firstDogFee, dogs, config().get("billing.familyDiscountPercentFromSecondDog", Integer.class));
    }
    public EntryFeeCalculator.PlanChange forPlanChange(Plan previous, Plan next, int lastPackSessions, boolean alreadyApplied) {
        return EntryFeeCalculator.forPlanChange(entryFee(next), previous.type(), next.type(), lastPackSessions, alreadyApplied,
                config().modules().contains(Module.PACKS), config().get("billing.packToMemberMinSessions", Integer.class),
                config().get("billing.packToMemberEntryDiscountPercent", Integer.class));
    }
    private void version(Plan old, Object expected) {
        if (!(expected instanceof Number number) || number.longValue() != old.version()) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
    private Plan build(Plan old, Map<String, Object> request) {
        var values = new LinkedHashMap<>(writer.fields(old)); values.putAll(request);
        var type = validator.convert(values.get("type"), PlanType.class);
        if (type == null) { throw validator.invalid("type", "VALIDATION_ERROR"); }
        boolean changedType = old != null && old.type() != type;
        if (old == null || changedType) {
            Module module = type == PlanType.PACK ? Module.PACKS : type == PlanType.SINGLE_CLASS ? Module.SINGLE_CLASS : null;
            if (module != null && !config().modules().contains(module)) { throw validator.invalid("type", "MODULE_DISABLED"); }
        }
        if (changedType && usage(old.id()).values().stream().anyMatch(count -> count > 0)) { throw new ApiException(ErrorCode.PLAN_IN_USE, new LinkedHashMap<>(usage(old.id()))); }
        if (type != PlanType.MONTHLY && request.get("billingMode") != null || type != PlanType.PACK && request.get("pack") != null
                || type != PlanType.SINGLE_CLASS && request.get("singleClass") != null) { throw validator.invalid("type", "VALIDATION_ERROR"); }
        String code = Objects.toString(values.get("code"), "");
        if (!code.matches("[A-Z0-9_]{1,16}")) { throw validator.invalid("code", "VALIDATION_ERROR"); }
        if (list(true).stream().anyMatch(item -> (old == null || !item.id().equals(old.id())) && code.equalsIgnoreCase(item.code()))) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, Map.of("field", "code"));
        }
        var name = validator.text(values.get("name"), "name", 60, true, config());
        var mode = type == PlanType.MONTHLY ? validator.convert(values.getOrDefault("billingMode", "MONTHLY_FEE"), BillingMode.class) : null;
        if (type == PlanType.MONTHLY && mode == null) { mode = BillingMode.MONTHLY_FEE; }
        int dogs = ((Number) values.getOrDefault("dogsIncluded", 1)).intValue();
        int order = ((Number) values.getOrDefault("order", list(true).stream().mapToInt(Plan::order).max().orElse(-10) + 10)).intValue();
        if (dogs < 1 || dogs > 9 || order < 0) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var texts = values.get("texts");
        if (old != null && request.get("texts") instanceof Map<?, ?> patch) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (writer.fields(old).get("texts") instanceof Map<?, ?> current) { current.forEach((k, v) -> merged.put(k.toString(), v)); }
            patch.forEach((k, v) -> { if (v != null) { merged.put(k.toString(), v); } }); texts = merged;
        }
        var now = clock.instant(); var actor = actors.current().accountId();
        return new Plan(old == null ? UUID.randomUUID().toString() : old.id(), TenantContext.require(), code, name, type, mode, dogs,
                validator.entryFee(values.get("entryFee"), config()), type == PlanType.PACK ? validator.pack(values.get("pack")) : null,
                type == PlanType.SINGLE_CLASS ? validator.single(values.get("singleClass"), config()) : null,
                validator.text(values.get("conditions"), "conditions", 200, false, config()), validator.texts(texts, config()),
                (Boolean) values.getOrDefault("showOnSignup", true), (Boolean) values.getOrDefault("showOnWeb", true), order,
                (Boolean) values.getOrDefault("active", true), old == null ? 0 : old.version() + 1,
                old == null ? now : old.createdAt(), now, old == null ? actor : old.createdByAccountId(), actor);
    }
    private void save(Plan old, Plan next, String action) {
        writer.save(OfferChanged.Kind.Plan, next == null ? old.id() : next.id(), writer.fields(old), next, action);
    }
    public String create(Map<String, Object> request) {
        return transactions.run(() -> { plans.lock(); var next = build(null, request); save(null, next, "CREATED"); return next.id(); });
    }
    public void update(String id, Map<String, Object> request) {
        transactions.run(() -> {
            plans.lock(); var old = get(id); version(old, request.get("version")); var next = build(old, request);
            save(old, next, old.active() == next.active() ? "UPDATED" : next.active() ? "REACTIVATED" : "DEACTIVATED"); return null;
        });
    }
    public void delete(String id) {
        transactions.run(() -> {
            plans.lock(); var old = get(id); var references = usage(id);
            if (references.values().stream().anyMatch(count -> count > 0)) { throw new ApiException(ErrorCode.PLAN_IN_USE, new LinkedHashMap<>(references)); }
            for (var price : prices.forPlan(id)) { writer.save(OfferChanged.Kind.Price, price.id(), writer.fields(price), null, "DELETED"); }
            save(old, null, "DELETED"); return null;
        });
    }
    public void order(List<String> ids) {
        transactions.run(() -> {
            plans.lock(); var current = list(true);
            if (ids.size() != current.size() || new HashSet<>(ids).size() != ids.size()
                    || !new HashSet<>(ids).equals(new HashSet<>(current.stream().map(Plan::id).toList()))) { throw new ApiException(ErrorCode.ORDER_INCOMPLETE); }
            for (var old : current) {
                int position = ids.indexOf(old.id()) * 10;
                if (position != old.order()) { save(old, build(old, Map.of("order", position)), "REORDERED"); }
            }
            return null;
        });
    }
}
