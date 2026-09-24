package com.agilityhub.core.clubs.signup.application;

import com.agilityhub.core.clubs.catalogs.application.SignupCatalog;
import com.agilityhub.core.clubs.signup.domain.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Application boundary for the pure S04 policies. Persistence is owned by census and payments. */
@Service
public class SignupPolicy {
    public record Price(String id, Money amount, String periodicity) { }
    /** Every current price of a plan, for the D2 plan selector (`planOptions`, E3-T08). */
    public record PlanPrice(String priceId, Money amount, String periodicity, String concept) { }
    public record Plan(String id, String type, String billingMode, int dogsIncluded, LocalizedText name,
            LocalizedText description, LocalizedText conditions, LocalizedText offerLabel, Map<String,Integer> pack,
            Price price, Money entryFee, Money maintenanceFee, List<PlanPrice> prices) {
        /**
         * The price the member is billed on (`Member.priceId`), picked by the plan's billing mode (E3-T08 round 2):
         * `MAINTENANCE` → the current `MAINTENANCE_FEE` price (Teràpia); otherwise the plan's standard price. `price` stays
         * the standard one, so the public offer and the upfront quote do not change (R-04-09, R-04-14).
         */
        public Price billedPrice() {
            if (!"MAINTENANCE".equals(billingMode)) { return price; }
            return prices.stream().filter(p -> "MAINTENANCE_FEE".equals(p.concept())).findFirst().map(p -> new Price(p.priceId(), p.amount(), p.periodicity())).orElse(null);
        }
    }
    public record Line(String concept, String dogId, Money amountDue) { }
    public record Period(String option, LocalDate startDate, Money amountDue) { }
    public record Quote(List<Line> lines, Money totalDue, Period firstMonth, Period additionalDog) { }
    /** One payable choice of a plan quote: `totalDue` = the plan's lines + this option (R-04-14/15). */
    public record QuoteOption(String option, String portion, LocalDate startDate, Money amountDue, Money totalDue) { }
    /** The `GET /signup` quote of one plan, computed with the submission's own {@link #quote} (E3-T08, M5). */
    public record PlanQuote(String planId, List<Line> lines, Money totalDue, List<QuoteOption> options) { }
    public record Consent(String type, boolean granted, String version, Instant acceptedAt, String locale, String ipHash, String source) { }
    public record Candidate(String clubId, String memberId, String status, String firstName, String lastName1,
            String lastName2, List<NamedDog> dogs) { }
    public record NamedDog(String name, String status) { }
    public record Match(String holderMemberId, String holderDisplayName) { }
    private final SignupCatalog catalogs;
    private final ClubConfigService configs;
    private final ParameterCatalog parameters;
    private final ClubClock clubClock;
    private final Clock clock;
    public SignupPolicy(SignupCatalog catalogs, ClubConfigService configs, ParameterCatalog parameters, ClubClock clubClock, Clock clock) {
        this.catalogs = catalogs; this.configs = configs; this.parameters = parameters; this.clubClock = clubClock; this.clock = clock;
    }
    public void lock() { catalogs.lock(); }
    private ClubConfig config() { return configs.get(TenantContext.require()); }
    public LocalDate today() { return clubClock.today(TenantContext.require()); }
    private SignupParameters parameters() { return SignupParameters.resolve(parameters, config().parameters()::get); }
    private List<SignupPlanCatalog.Offer> offers(LocalDate date) {
        return SignupPlanCatalog.list(TenantContext.require(), catalogs.at(date), config().modules(), parameters().entryFeePerDog());
    }
    private List<SignupPlanCatalog.Offer> assignable(LocalDate date) {
        return SignupPlanCatalog.assignable(TenantContext.require(), catalogs.at(date), config().modules(), parameters().entryFeePerDog());
    }
    /** The public offer: `GET /signup` plans and the applicant's `planIdRequested` (`showOnSignup`). */
    public List<Plan> plans() { return offers(today()).stream().map(this::plan).toList(); }
    /** Everything the club may assign, hidden plans included (M8): D2, the family fare and the member's own plan. */
    public List<Plan> assignablePlans() { return assignable(today()).stream().map(this::plan).toList(); }
    public Plan require(String id) {
        var offers = offers(today());
        if (id == null && offers.isEmpty()) { return null; }
        return plan(SignupPlanCatalog.require(offers, id));
    }
    public Plan requireAssignable(String id) {
        var plans = assignable(today());
        if (id == null && offers(today()).isEmpty()) { return null; }
        return plan(SignupPlanCatalog.require(plans, id));
    }
    private Plan plan(SignupPlanCatalog.Offer offer) {
        var price = offer.price();
        return new Plan(offer.id(), offer.type().name(), offer.billingMode(), offer.dogsIncluded(), offer.name(), offer.description(),
                offer.conditions(), offer.offerLabel(), offer.pack() == null ? null : Map.of("sessions", offer.pack().sessions(), "validityMonths", offer.pack().validityMonths()),
                price == null ? null : new Price(price.id(), price.amount(), offer.type().name().equals("MONTHLY") ? "MONTHLY" : "ONE_OFF"), offer.entryFee(), offer.maintenanceFee(),
                offer.prices().stream().map(p -> new PlanPrice(p.id(), p.amount(), periodicity(offer, p.concept().name()), p.concept().name())).toList());
    }
    private static String periodicity(SignupPlanCatalog.Offer offer, String concept) {
        return concept.equals("MAINTENANCE_FEE") || concept.equals("MONTHLY_FEE") && offer.type().name().equals("MONTHLY") ? "MONTHLY" : "ONE_OFF";
    }
    /**
     * R-04-13: the family fare for a group of `dogs` dogs = the assignable `MONTHLY_FEE` plan with a current price and the
     * fewest `dogsIncluded` ≥ max(2, dogs). The same rule proposes the plan at D2, prices the add-dog fee and fills
     * `{twoDogsMonthlyFee}` in `signup.text.familyGroupIntro`.
     */
    public Optional<Plan> familyFare(int dogs) {
        return assignablePlans().stream().filter(p -> "MONTHLY_FEE".equals(p.billingMode()) && p.price() != null && p.dogsIncluded() >= Math.max(2, dogs))
                .min(Comparator.comparingInt(Plan::dogsIncluded));
    }
    /**
     * The D2 proposal (R-04-13): a group with ≥ 2 dogs gets the family fare «if it exists». When no plan includes that
     * many dogs (a third dog at the Cànic), the proposal is the largest family fare; the add-dog fee keeps {@link #familyFare}.
     */
    public Optional<Plan> proposedFamilyFare(int dogs) {
        return familyFare(dogs).or(() -> assignablePlans().stream().filter(p -> "MONTHLY_FEE".equals(p.billingMode()) && p.price() != null && p.dogsIncluded() >= 2)
                .max(Comparator.comparingInt(Plan::dogsIncluded)));
    }
    public Money additionalFee(String currentPlanId, String requestedPlanId) {
        var current = assignablePlans().stream().filter(p -> p.id().equals(currentPlanId)).findFirst().orElse(null);
        var standard = requireAssignable(requestedPlanId);
        Money zero = new Money(0, config().club().currency());
        if (standard == null || standard.price() == null || !"MONTHLY_FEE".equals(standard.billingMode())) { return zero; }
        var resulting = familyFare(current == null ? 2 : current.dogsIncluded() + 1).orElse(null);
        return UpfrontLines.additionalMonthlyFee(current == null || current.price() == null ? null : current.price().amount(),
                resulting == null ? null : resulting.price().amount(), standard.price().amount(), parameters().familyDiscountPercentFromSecondDog());
    }
    /**
     * `GET /signup` quotes (R-04-14/15), one per plan, each computed by {@link #quote} exactly as the submission will:
     * the option-independent lines, then one option per first-month choice (public signup, `MONTHLY_FEE` plans with a
     * current price) or per additional-dog choice (add-dog mode, `currentPlanId` = the member's plan).
     */
    public PlanQuote planQuote(Plan plan, boolean addDog, String currentPlanId) {
        LocalDate today = today();
        var base = quote(plan.id(), null, addDog, currentPlanId, "TODAY", today);
        var lines = base.lines().stream().filter(l -> !Set.of("FIRST_MONTH", "ADDITIONAL_DOG_FEE").contains(l.concept())).toList();
        Money total = new Money(0, config().club().currency());
        for (var line : lines) { total = total.plus(line.amountDue()); }
        var options = new ArrayList<QuoteOption>();
        if (addDog && base.additionalDog() != null) {
            for (var choice : additionalOptions(currentPlanId, plan.id())) {
                var quote = quote(plan.id(), null, true, currentPlanId, choice.option(), today);
                options.add(new QuoteOption(choice.option(), "FULL", choice.startDate(), quote.additionalDog().amountDue(), quote.totalDue()));
            }
        } else if (!addDog && base.firstMonth() != null) {
            for (var choice : FirstMonthCalculator.options(today, parameters().firstMonthSplitDay(), parameters().nextInvoiceDayOfMonth(), plan.price().amount())) {
                var quote = quote(plan.id(), null, false, null, choice.option().name(), today);
                options.add(new QuoteOption(choice.option().name(), choice.portion() == FirstMonthCalculator.Portion.FULL_MONTH ? "FULL" : "HALF",
                        quote.firstMonth().startDate(), quote.firstMonth().amountDue(), quote.totalDue()));
            }
        }
        return new PlanQuote(plan.id(), lines, total, List.copyOf(options));
    }
    public Quote quote(String planId, String dogId, boolean addDog, String currentPlanId, String option, LocalDate date) {
        // `planId` was checked by the caller against the list that applies (public offer or assignable plans).
        var plan = planId == null ? null : SignupPlanCatalog.require(assignable(date), planId);
        FirstMonthCalculator.Option selected;
        try { selected = FirstMonthCalculator.Option.valueOf(option == null ? "TODAY" : option); }
        catch (IllegalArgumentException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        boolean billing = config().modules().contains(com.agilityhub.core.platform.application.Module.BILLING);
        var quote = addDog ? UpfrontLines.addDog(plan, dogId, billing, date, parameters(), additionalFee(currentPlanId, planId), selected)
                : UpfrontLines.publicSignup(plan, dogId, billing, date, parameters(), selected);
        return new Quote(quote.lines().stream().map(l -> new Line(l.concept().name(), l.dogId(), l.amountDue())).toList(),
                quote.totalDue(), period(quote.firstMonth()), period(quote.additionalDog()));
    }
    private Period period(UpfrontLines.Period value) { return value == null ? null : new Period(value.option().name(), value.startDate(), value.amountDue()); }
    public List<Period> firstOptions(Money monthly) {
        return FirstMonthCalculator.options(today(), parameters().firstMonthSplitDay(), parameters().nextInvoiceDayOfMonth(), monthly)
                .stream().map(c -> new Period(c.option().name(), c.startDate(), c.amountDue())).toList();
    }
    public List<Period> additionalOptions(String current, String requested) {
        return UpfrontLines.additionalDogOptions(today(), additionalFee(current, requested), parameters().upfrontCutoffDay()).stream().map(this::period).toList();
    }
    public LocalDate nextInvoice(Period period) {
        LocalDate month = period.startDate().plusMonths(1).withDayOfMonth(1);
        return month.withDayOfMonth(Math.min(parameters().nextInvoiceDayOfMonth(), month.lengthOfMonth()));
    }
    public List<Map<String,Object>> emails(List<String> raw) {
        return EmailRules.normalize(raw).stream().map(e -> Map.<String,Object>of("email", e.email(), "primary", e.primary(), "bounced", e.bounced())).toList();
    }
    private CountryContactRules country() { return new CountryContactRules(config().countryProfile()); }
    public String document(String type, String value) { return IdDocumentValidator.validate(country(), type, value).value(); }
    /** The normalised chip, or `400 VALIDATION_ERROR` on `field` when it does not fit the club's country profile (§3 `Dog`). */
    public String chip(String raw, String field) {
        String chip = DogChips.normalize(raw);
        if (!DogChips.valid(country().code(), chip)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", field, "code", "INVALID_VALUE"))));
        }
        return chip;
    }
    public List<Map<String,Object>> phones(List<Map<String,Object>> raw) {
        return PhoneNormalizer.normalize(country(), raw.stream().map(p -> new PhoneNormalizer.Input((String)p.get("prefix"), (String)p.get("number"), (String)p.get("label"))).toList())
                .stream().map(p -> { var result = new LinkedHashMap<String,Object>(); result.put("prefix", p.prefix()); result.put("number", p.number()); if(p.label()!=null) result.put("label",p.label()); return (Map<String,Object>)result; }).toList();
    }
    public List<Map<String,String>> towns(String postalCode) { return PostalCodeTowns.lookup(country(), postalCode).towns().stream().map(t -> Map.of("name",t.name(),"region",t.region())).toList(); }
    public String town(String postalCode, String town) { return PostalCodeTowns.select(PostalCodeTowns.lookup(country(), postalCode), town); }
    private List<ConsentPolicy.Entry> history(List<Consent> entries) { return entries.stream().map(e -> new ConsentPolicy.Entry(ConsentPolicy.Type.valueOf(e.type()),e.granted(),e.version(),e.acceptedAt(),e.locale(),e.ipHash(),e.source())).toList(); }
    public boolean currentConsent(List<Consent> entries, String version) { return !ConsentPolicy.requiresAcceptance(history(entries),new ConsentPolicy.Legal(version)); }
    public List<Consent> consent(Boolean accepted, String version, Boolean image, String legalVersion, boolean addDog, List<Consent> history, String locale, String ipHash) {
        var request = accepted == null ? null : new ConsentPolicy.Request(accepted,version,image);
        return ConsentPolicy.entries(request,new ConsentPolicy.Legal(legalVersion),addDog,history(history),clock,locale,ipHash).stream()
                .map(e -> new Consent(e.type().name(),e.granted(),e.version(),e.acceptedAt(),e.locale(),e.ipHash(),e.source())).toList();
    }
    public Optional<Match> family(String holder, String dog, List<Candidate> candidates) {
        return FamilyHolderMatcher.match(TenantContext.require(),holder,dog,candidates.stream().map(c -> new FamilyHolderMatcher.Candidate(c.clubId(),c.memberId(),
                FamilyHolderMatcher.MemberStatus.valueOf(c.status()),c.firstName(),c.lastName1(),c.lastName2(),c.dogs().stream()
                .map(d -> new FamilyHolderMatcher.Dog(d.name(),FamilyHolderMatcher.DogStatus.valueOf(d.status()))).toList())).toList())
                .map(m -> new Match(m.holderMemberId(),m.holderDisplayName()));
    }
}
