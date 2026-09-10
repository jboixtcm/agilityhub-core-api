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
    public record Plan(String id, String type, String billingMode, int dogsIncluded, LocalizedText name,
            LocalizedText description, LocalizedText conditions, LocalizedText offerLabel, Map<String,Integer> pack,
            Price price, Money entryFee, Money maintenanceFee) { }
    public record Line(String concept, String dogId, Money amountDue) { }
    public record Period(String option, LocalDate startDate, Money amountDue) { }
    public record Quote(List<Line> lines, Money totalDue, Period firstMonth, Period additionalDog) { }
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
    public List<Plan> plans() { return offers(today()).stream().map(this::plan).toList(); }
    public Plan require(String id) {
        var offers = offers(today());
        if (id == null && offers.isEmpty()) { return null; }
        return plan(SignupPlanCatalog.require(offers, id));
    }
    private Plan plan(SignupPlanCatalog.Offer offer) {
        var price = offer.price();
        return new Plan(offer.id(), offer.type().name(), offer.billingMode(), offer.dogsIncluded(), offer.name(), offer.description(),
                offer.conditions(), offer.offerLabel(), offer.pack() == null ? null : Map.of("sessions", offer.pack().sessions(), "validityMonths", offer.pack().validityMonths()),
                price == null ? null : new Price(price.id(), price.amount(), offer.type().name().equals("MONTHLY") ? "MONTHLY" : "ONE_OFF"), offer.entryFee(), offer.maintenanceFee());
    }
    public Money additionalFee(String currentPlanId, String requestedPlanId) {
        var plans = plans();
        var current = plans.stream().filter(p -> p.id().equals(currentPlanId)).findFirst().orElse(null);
        var standard = require(requestedPlanId);
        Money zero = new Money(0, config().club().currency());
        if (standard == null || standard.price() == null || !"MONTHLY_FEE".equals(standard.billingMode())) { return zero; }
        var resulting = plans.stream().filter(p -> p.dogsIncluded() >= (current == null ? 2 : current.dogsIncluded() + 1))
                .filter(p -> "MONTHLY_FEE".equals(p.billingMode()) && p.price() != null).min(Comparator.comparingInt(Plan::dogsIncluded)).orElse(null);
        return UpfrontLines.additionalMonthlyFee(current == null || current.price() == null ? null : current.price().amount(),
                resulting == null ? null : resulting.price().amount(), standard.price().amount(), parameters().familyDiscountPercentFromSecondDog());
    }
    public Quote quote(String planId, String dogId, boolean addDog, String currentPlanId, String option, LocalDate date) {
        var available = offers(date);
        var plan = planId == null && available.isEmpty() ? null : SignupPlanCatalog.require(available, planId);
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
