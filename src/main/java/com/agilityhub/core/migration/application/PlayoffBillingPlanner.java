package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.clubs.catalogs.application.MigrationCatalogAccess;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.payments.application.BillingMigrationAccess;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Steps 7/8 are validated against the same census plan before any batch is written. */
@Service
public class PlayoffBillingPlanner {
    public record Plan(List<BillingMigrationAccess.Receipt> receipts, List<BillingMigrationAccess.Pack> packs,
            Map<String, Long> forecast, List<MigrationReport.Entry> rows, Map<String, Long> counters) { }
    private final CensusMigrationService census; private final MigrationCatalogAccess catalogs;
    private final BillingMigrationAccess billing; private final ClubConfigService configs;
    public PlayoffBillingPlanner(CensusMigrationService census, MigrationCatalogAccess catalogs, BillingMigrationAccess billing, ClubConfigService configs) {
        this.census = census; this.catalogs = catalogs; this.billing = billing; this.configs = configs;
    }
    public Plan plan(PlayoffInput input, MappingConfig mapping, PlayoffPlanner.Plan censusPlan, LocalDate cutover) {
        var members = new LinkedHashMap<String, Map<String, Object>>(); var dogs = new LinkedHashMap<String, Map<String, Object>>();
        var existingMandates = new HashSet<String>();
        for (var member : census.snapshot("members")) { members.put(string(member.get("_id")), member); }
        members.forEach((id, member) -> { if (map(member.get("paymentMethod")).get("mandateRef") != null) { existingMandates.add(id); } });
        for (var dog : census.snapshot("dogs")) { dogs.put(string(dog.get("_id")), dog); }
        for (var change : censusPlan.changes()) {
            if (change.entity().equals("members")) { members.put(change.id(), change.fields()); }
            if (change.entity().equals("dogs")) { dogs.put(change.id(), change.fields()); }
        }
        var bySource = new HashMap<String, String>();
        members.forEach((id, member) -> {
            Object source = map(member.get("sourceIds")).get("playoffMemberId"); if (source != null) { bySource.put(source.toString(), id); }
            Object aliases = map(member.get("externalIds")).get("playoff"); if (aliases instanceof List<?> values) { values.forEach(value -> bySource.put(value.toString(), id)); }
        });
        // A merged source row can be represented only in the planned externalIds array.
        censusPlan.changes().stream().filter(c -> c.entity().equals("members")).forEach(c -> bySource.put(c.source().get("id"), c.id()));
        var planByCode = new HashMap<String, Map<String, Object>>(); catalogs.rows("plans").forEach(p -> planByCode.put(string(p.get("code")), p));
        var config = configs.get(TenantContext.require()); var rows = new ArrayList<MigrationReport.Entry>(); var counters = new TreeMap<String, Long>();
        counters.put("mandatesCreated", members.entrySet().stream().filter(e -> !existingMandates.contains(e.getKey())
                && map(e.getValue().get("paymentMethod")).get("mandateRef") != null).count());
        var receipts = new ArrayList<BillingMigrationAccess.Receipt>(); var packs = new ArrayList<BillingMigrationAccess.Pack>();
        var receiptSources = billing.receiptSources(); var packSources = billing.packSources();
        var receiptNumbers = billing.receiptNumberSources();
        if (config.modules().contains(Module.BILLING)) {
            for (var row : input.files().getOrDefault("receipts", List.of())) {
                try {
                    String source = required(row, "id"); var date = date(required(row, "date"));
                    if (date.isBefore(cutover.minusMonths(mapping.history().receiptsMonths())) || date.isAfter(cutover)) { add(rows, row, "invoices", "SKIPPED", ""); continue; }
                    String memberId = bySource.get(required(row, "memberId"));
                    if (memberId == null) { skipReceipt(rows, row, "MAPPING_INVALID"); continue; }
                    var member = members.get(memberId); String original = required(row, "number");
                    long number = Long.parseLong(original.replaceAll("[^0-9]", ""));
                    if (receiptNumbers.containsKey(number) && !source.equals(receiptNumbers.get(number))) {
                        skipReceipt(rows, row, "NUMBER_CONFLICT"); continue;
                    }
                    String status = switch (MappingConfig.normalize(required(row, "status"))) {
                        case "pagado", "pagat", "paid" -> "PAID";
                        case "pendiente", "pendent", "vencido", "vençut", "pending" -> "PENDING";
                        case "impagado", "impagat", "failed" -> "FAILED";
                        default -> throw invalid();
                    };
                    long total = amount(required(row, "total")); long tax = row.get("tax").isEmpty() ? 0 : amount(row.get("tax"));
                    String currency = row.get("currency").isEmpty() ? config.club().currency() : row.get("currency");
                    String method = method(row.get("method"));
                    var receipt = new BillingMigrationAccess.Receipt(source, number, original, date,
                            period(row.get("concept"), date), memberId, member.get("memberNumber") instanceof Number n ? n.intValue() : null,
                            name(member), string(map(member.get("paymentMethod")).get("holderTaxId")), row.get("concept"),
                            new Money(Math.subtractExact(total, tax), currency), row.get("taxPercent").isEmpty() ? BigDecimal.ZERO : decimal(row.get("taxPercent")),
                            new Money(tax, currency), new Money(total, currency), method, status);
                    receiptNumbers.put(number, source);
                    boolean existing = !receiptSources.add(source);
                    add(rows, row, "invoices", existing ? "SKIPPED" : "CREATED", ""); counters.merge("invoices" + status, 1L, Long::sum);
                    if (!existing) { receipts.add(receipt); }
                // R-18-11b (E11-T15 step 6): a row the importer cannot read — an unknown status, an unparseable date, number or amount,
                // a missing field — is skipped with a warning; it never refuses the whole load (the census included).
                } catch (RuntimeException invalid) { skipReceipt(rows, row, "INPUT_SCHEMA_MISMATCH"); }
            }
        }
        if (config.modules().contains(Module.PACKS)) {
            for (var row : input.files().getOrDefault("packs", List.of())) {
                try {
                    String source = required(row, "id"), memberId = bySource.get(required(row, "memberId"));
                    if (memberId == null) { throw invalid(); }
                    // R-18-10: prefer the dog of the source record, then the input's record order, never an internal UUID.
                    var sourceOrder = new HashMap<String, Integer>();
                    var sourceMembers = input.files().getOrDefault("members", List.of());
                    for (int index = 0; index < sourceMembers.size(); index++) { sourceOrder.put(sourceMembers.get(index).get("id"), index); }
                    var memberDogs = dogs.entrySet().stream().filter(e -> memberId.equals(e.getValue().get("memberId")))
                            .sorted(Comparator.<Map.Entry<String, Map<String, Object>>>comparingInt(e ->
                                    row.get("memberId").equals(map(e.getValue().get("sourceIds")).get("playoffMemberId")) ? -1 :
                                            sourceOrder.getOrDefault(string(map(e.getValue().get("sourceIds")).get("playoffMemberId")), Integer.MAX_VALUE))
                                    .thenComparing(Map.Entry::getKey)).toList();
                    if (memberDogs.isEmpty()) { throw invalid(); }
                    var plan = packPlan(row.get("plan"), mapping, planByCode, members.get(memberId));
                    if (plan == null) { throw invalid(); }
                    var terms = map(plan.get("pack")); int total = ((Number) terms.get("sessions")).intValue(); int months = ((Number) terms.get("validityMonths")).intValue();
                    int consumed = Integer.parseInt(required(row, "consumed")); if (consumed < 0 || consumed > total) { throw invalid(); }
                    var opened = date(required(row, "openedOn")); var expires = opened.plusMonths(months).minusDays(1);
                    if (memberDogs.size() > 1) { add(rows, row, "packBalances", "WARNING", "PACK_DOG_AMBIGUOUS"); }
                    // E11-T15 step 9: a pack with nothing left is ACTIVE with remaining 0 (PackBalanceService.openMigrated), never CLOSED.
                    String state = expires.isBefore(cutover) ? "EXPIRED" : "ACTIVE";
                    boolean existing = !packSources.add(source); add(rows, row, "packBalances", existing ? "SKIPPED" : "CREATED", "");
                    counters.merge("packs" + state, 1L, Long::sum);
                    if (!existing) { packs.add(new BillingMigrationAccess.Pack(source, memberId, memberDogs.getFirst().getKey(), string(plan.get("_id")), opened, consumed, cutover)); }
                // R-18-11b (E11-T15 step 6): an unreadable pack row (unknown plan or member, a bad date or count) is skipped with a warning.
                } catch (RuntimeException invalid) { add(rows, row, "packBalances", "WARNING", "INPUT_SCHEMA_MISMATCH"); add(rows, row, "packBalances", "SKIPPED", ""); }
            }
        }
        var forecast = new TreeMap<String, Long>();
        for (var row : input.files().getOrDefault("forecast", List.of())) {
            try {
                String memberId = bySource.get(required(row, "memberId")); if (memberId == null) { throw invalid(); }
                if (!YearMonth.parse(required(row, "period")).equals(YearMonth.from(cutover).plusMonths(1))) { throw invalid(); }
                forecast.merge(memberId, amount(required(row, "total")), Math::addExact);
            // R-18-11b (E11-T15 round 2, N6): `previsio.csv` only feeds the reconciliation; an unreadable row is skipped with a warning.
            } catch (RuntimeException invalid) { add(rows, row, "reconciliation", "WARNING", "INPUT_SCHEMA_MISMATCH"); add(rows, row, "reconciliation", "SKIPPED", ""); }
        }
        return new Plan(List.copyOf(receipts), List.copyOf(packs), Map.copyOf(forecast), List.copyOf(rows), Map.copyOf(counters));
    }
    private static void add(List<MigrationReport.Entry> rows, PlayoffInput.Row row, String entity, String outcome, String code) {
        rows.add(new MigrationReport.Entry(row.file(), row.row(), entity, outcome, code));
    }
    private static void skipReceipt(List<MigrationReport.Entry> rows, PlayoffInput.Row row, String code) {
        add(rows, row, "invoices", "WARNING", code);
        add(rows, row, "invoices", "SKIPPED", "");
    }
    /**
     * E11-T15 step 6 (R-18-11b): a pack row's plan — the Playoff typology through the mapping («Pack 10 classes» → `PACK10`), an
     * exact seed code, then the member's own plan; only a `PACK` plan with its terms. Null = none: the row is skipped with a warning.
     */
    private static Map<String, Object> packPlan(String key, MappingConfig mapping, Map<String, Map<String, Object>> planByCode, Map<String, Object> member) {
        var candidates = new ArrayList<Map<String, Object>>();
        String code = key.isEmpty() ? null : mapping.plans().get(MappingConfig.normalize(key));
        if (code != null && planByCode.containsKey(code)) { candidates.add(planByCode.get(code)); }
        if (planByCode.containsKey(key)) { candidates.add(planByCode.get(key)); }
        planByCode.values().stream().filter(p -> member != null && Objects.equals(p.get("_id"), member.get("planId"))).findFirst().ifPresent(candidates::add);
        return candidates.stream().filter(p -> "PACK".equals(p.get("type")) && p.get("pack") instanceof Map).findFirst().orElse(null);
    }
    /** The census's dates (E11-T15 step 6): ISO or strict `d/M/uuuu` («1/2/2026»), as {@link PlayoffPlanner#date}; empty is unreadable. */
    static LocalDate date(String value) {
        var date = PlayoffPlanner.date(value);
        if (date == null) { throw invalid(); }
        return date;
    }
    static BigDecimal decimal(String value) {
        String number = value.replace("€", "").replaceAll("\\s", "");
        if (number.contains(",")) { number = number.replace(".", "").replace(',', '.'); }
        return new BigDecimal(number);
    }
    static long amount(String value) { return decimal(value).movePointRight(2).longValueExact(); }
    static YearMonth period(String concept, LocalDate fallback) {
        var year = java.util.regex.Pattern.compile("\\b(20\\d{2})\\b").matcher(concept);
        if (year.find()) {
            String[] months = {"gener|enero", "febrer|febrero", "març|marzo", "abril", "maig|mayo", "juny|junio", "juliol|julio", "agost|agosto", "setembre|septiembre", "octubre", "novembre|noviembre", "desembre|diciembre"};
            for (int month = 0; month < months.length; month++) {
                if (java.util.regex.Pattern.compile("(?iu)\\b(" + months[month] + ")\\b").matcher(concept).find()) { return YearMonth.of(Integer.parseInt(year.group()), month + 1); }
            }
        }
        return YearMonth.from(fallback);
    }
    private static String method(String value) {
        return switch (MappingConfig.normalize(value)) {
            case "sepa_dd", "domiciliació bancària", "domiciliacion", "domiciliación" -> "SEPA_DD";
            case "card", "targeta", "tarjeta" -> "CARD";
            default -> "MANUAL";
        };
    }
    private static String name(Map<String, Object> member) {
        return String.join(" ", List.of("firstName", "lastName1", "lastName2").stream().map(member::get).filter(Objects::nonNull).map(Object::toString).filter(s -> !s.isBlank()).toList());
    }
    private static String required(PlayoffInput.Row row, String field) { String value = row.get(field); if (value.isEmpty()) { throw invalid(); } return value; }
    private static ApiException invalid() { return new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
}
