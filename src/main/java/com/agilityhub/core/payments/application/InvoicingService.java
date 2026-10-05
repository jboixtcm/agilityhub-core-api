package com.agilityhub.core.payments.application;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.shared.application.InactivityFeePort;
import com.agilityhub.core.shared.application.LeaveBillingPort;
import com.agilityhub.core.payments.domain.BillingIncidentCode;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PendingChargeRepository;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.PendingCharge;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.PaymentProviderFlags;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.text.Collator;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * S12 R-12-01…06 over the open club (E8-T02): {@link #linesFor} is `InvoicingService.linesFor(member, period)` of S12 §6, and
 * {@link #plan} the whole month for the simulation and the run. The rules are {@link InvoicingRules} (pure); this class only
 * gathers what they read — the census through {@link BillingCensusAccess}, the plans and current prices through
 * `BillingCatalogAccess` (`PriceResolver`), S13 through the inactivity and leave ports, the unbilled `PendingCharge`s — and the
 * club's parameters and modules. The issue date is today in the club's time zone (R-12-06, R-12-30).
 */
@Service
public class InvoicingService {
    /** The club's billing settings of one simulation or run. */
    public record Context(ClubConfig config, InvoicingRules.Settings settings, LocalDate issueDate, Locale locale) {
        public String clubId() { return config.club().id(); }
        public String currency() { return config.club().currency(); }
        public boolean module(Module module) { return config.modules().contains(module); }
        public <T> T parameter(String key, Class<T> type) { return config.get(key, type); }
    }
    /**
     * The month as the rules decided it, with what the simulation and the run need to write it. `waiting`: the manual receipts
     * waiting for «the next remittance» (R-12-19), in their numbers' order, each with its incident or none (E8-T07 step 3).
     */
    public record MonthPlan(Context context, YearMonth period, InvoicingRules.Month month, Map<String, BillingMember> members,
            Map<String, PendingCharge> charges, List<WaitingReceipt> waiting) {
        public BillingMember member(String id) { return members.get(id); }
        /** The waiting receipts the run remits (no incident). */
        public List<Invoice> remitted() { return waiting.stream().filter(receipt -> receipt.incident() == null).map(WaitingReceipt::invoice).toList(); }
    }
    /** A waiting receipt, its member's current name (else the one frozen on it) and why it cannot ride this run, if so. */
    public record WaitingReceipt(Invoice invoice, String memberName, BillingIncidentCode incident) { }

    private final BillingCensusAccess census; private final BillingCatalogAccess catalog; private final InactivityFeePort inactivity;
    private final LeaveBillingPort leave; private final PendingChargeRepository charges; private final InvoiceRepository invoices; private final ClubConfigService configs;
    private final BillingProviderSettings providers; private final BillingTexts texts; private final ClubClock clock;
    public InvoicingService(BillingCensusAccess census, BillingCatalogAccess catalog, InactivityFeePort inactivity, LeaveBillingPort leave,
            PendingChargeRepository charges, InvoiceRepository invoices, ClubConfigService configs, BillingProviderSettings providers, BillingTexts texts,
            ClubClock clock) {
        this.census = census; this.catalog = catalog; this.inactivity = inactivity; this.leave = leave; this.charges = charges; this.invoices = invoices;
        this.configs = configs; this.providers = providers; this.texts = texts; this.clock = clock;
    }

    public Context context() {
        String club = TenantContext.require();
        var config = configs.get(club);
        var methods = providers.enabledProviders().stream().map(PaymentProviderFlags::method).filter(Objects::nonNull)
                .map(PaymentMethodType::valueOf).collect(Collectors.toSet());
        var settings = new InvoicingRules.Settings(config.club().currency(), integer(config, "billing.nextInvoiceDayOfMonth", 1),
                InvoicingRules.CashInvoicing.valueOf(Objects.requireNonNullElse(config.get("billing.cashInvoicing", String.class), "MONTHLY")),
                integer(config, "billing.cashPeriodMonths", 6), !Boolean.FALSE.equals(config.get("billing.taxIncluded", Boolean.class)),
                config.modules().contains(Module.FAMILY_GROUP), config.modules().contains(Module.INACTIVITY), config.modules().contains(Module.SINGLE_CLASS),
                methods);
        return new Context(config, settings, clock.today(club), Locale.forLanguageTag(config.club().defaultLocale()));
    }

    /** S12 §6 `InvoicingService.linesFor(member, period)`: one member's lines of {@code period}, or why it has none (R-12-01…05). */
    public InvoicingRules.Outcome linesFor(BillingMember member, YearMonth period) {
        var context = context();
        var pending = unbilled(List.of(member.id()));
        return InvoicingRules.linesFor(member(member), period, context.issueDate(), context.settings(), sources(context, pending), describer(context));
    }

    /** The club's month: every `ACTIVE` member and the family groups, in the numbering order (R-12-08). */
    public MonthPlan plan(YearMonth period) {
        var context = context();
        var members = new ArrayList<>(census.activeMembers());
        members.sort(order(context.locale()));
        var byId = new LinkedHashMap<String, BillingMember>();
        members.forEach(member -> byId.put(member.id(), member));
        var pending = unbilled(byId.keySet());
        var groups = census.activeFamilyGroups().stream().map(group -> new InvoicingRules.Group(group.holderMemberId(), group.memberIds())).toList();
        var month = InvoicingRules.month(members.stream().map(InvoicingService::member).toList(), groups, period, context.issueDate(), context.settings(),
                sources(context, pending), describer(context));
        var chargesById = new LinkedHashMap<String, PendingCharge>();
        pending.values().forEach(list -> list.forEach(charge -> chargesById.put(charge.id(), charge)));
        return new MonthPlan(context, period, month, byId, chargesById, waiting(context));
    }

    /**
     * R-12-19 (E8-T07 step 3): the club's waiting `includeInNextRun` receipts (positive totals only, ruling E89), each checked
     * against its member as it is now ({@link InvoicingRules#waitingReceiptIncident}): the simulation previews and counts the
     * remitted ones and lists the others as incidents; the run remits the former and skips the latter.
     */
    private List<WaitingReceipt> waiting(Context context) {
        var waiting = new ArrayList<WaitingReceipt>();
        for (var receipt : invoices.forNextRun()) {
            var member = census.member(receipt.memberId()).orElse(null);
            var incident = InvoicingRules.waitingReceiptIncident(receipt.paymentMethod().mandateRef(), member == null ? null : member(member),
                    member == null || member.paymentMethod() == null ? null : member.paymentMethod().mandateRef(), context.settings());
            waiting.add(new WaitingReceipt(receipt, member == null ? receipt.memberSnapshot().fullName() : member.fullName(), incident));
        }
        return waiting;
    }

    /** R-12-08: last names, first name, member number — deterministic, so a rollback and a new run number alike. */
    static Comparator<BillingMember> order(Locale locale) {
        var collator = Collator.getInstance(locale); collator.setStrength(Collator.SECONDARY);
        Comparator<String> text = (a, b) -> collator.compare(a == null ? "" : a, b == null ? "" : b);
        return Comparator.comparing(BillingMember::lastName1, text).thenComparing(BillingMember::lastName2, text).thenComparing(BillingMember::firstName, text)
                .thenComparing(member -> member.memberNumber() == null ? Integer.MAX_VALUE : member.memberNumber()).thenComparing(BillingMember::id);
    }

    static InvoicingRules.Member member(BillingMember member) {
        var method = member.paymentMethod();
        PaymentMethodType type = null;
        if (method != null && method.type() != null) {
            try { type = PaymentMethodType.valueOf(method.type()); } catch (IllegalArgumentException unknown) { type = null; }
        }
        return new InvoicingRules.Member(member.id(), member.status(), member.nextInvoiceDate(), type, method != null && method.bankAccount(),
                method != null && method.cardInvalid(), member.planId());
    }
    private Map<String, List<PendingCharge>> unbilled(Collection<String> memberIds) {
        return charges.unbilled(memberIds).stream().collect(Collectors.groupingBy(PendingCharge::memberId, LinkedHashMap::new, Collectors.toList()));
    }
    private InvoicingRules.Sources sources(Context context, Map<String, List<PendingCharge>> pending) {
        var taxes = new HashMap<String, BigDecimal>();
        return new InvoicingRules.Sources() {
            @Override public Optional<InvoicingRules.Plan> plan(String planId) {
                return catalog.plan(planId).map(plan -> new InvoicingRules.Plan(plan.id(), InvoicingRules.PlanType.valueOf(plan.type()),
                        "MAINTENANCE".equals(plan.billingMode()), BillingTexts.text(plan.name(), context.locale())));
            }
            @Override public Optional<InvoicingRules.Price> price(String planId, InvoiceLineOrigin concept, LocalDate day) {
                return catalog.currentPrice(planId, concept.name(), day).map(price -> new InvoicingRules.Price(price.id(), price.amount(), price.taxPercent()));
            }
            @Override public Optional<Money> inactivityFee(String memberId, YearMonth month) { return inactivity.feeFor(memberId, month); }
            @Override public Optional<YearMonth> lastInvoicedMonth(String memberId) { return leave.lastInvoicedMonth(memberId); }
            @Override public List<InvoicingRules.Charge> charges(String memberId) {
                return pending.getOrDefault(memberId, List.of()).stream().map(charge -> new InvoicingRules.Charge(charge.id(), charge.bookingId(), charge.priceId(),
                        charge.amount(), taxes.computeIfAbsent(String.valueOf(charge.priceId()), id -> catalog.price(charge.priceId())
                                .map(BillingCatalogAccess.BillingPrice::taxPercent).orElse(BigDecimal.ZERO)), charge.description())).toList();
            }
        };
    }
    private InvoicingRules.Describer describer(Context context) {
        return (origin, planName, month) -> texts.line(origin, planName, month, context.locale());
    }
    private static int integer(ClubConfig config, String key, int fallback) {
        Integer value = config.get(key, Integer.class);
        return value == null ? fallback : value;
    }
}
